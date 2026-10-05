package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.CloseAssertion;
import io.github.unlocker.archrag.canonicalmodel.command.DeferRelation;
import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.graphprojector.GraphProjection.AppliedRecord;
import io.github.unlocker.archrag.identityresolution.FeatureExtractor;
import io.github.unlocker.archrag.identityresolution.IdentityCandidates;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import io.github.unlocker.archrag.identityresolution.IdentityStoreException;
import io.github.unlocker.archrag.normalizer.NormalizationResult;
import io.github.unlocker.archrag.normalizer.Normalizer;
import io.github.unlocker.archrag.normalizer.UnresolvedReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;

/**
 * Ведёт одно событие журнала от {@code RECEIVED} до конечного статуса: маршрутизирует по {@code type},
 * нормализует, получает {@code gid}, записывает в граф и двигает статус и checkpoint.
 *
 * <p>Маршрут по {@code type} идёт до {@link Normalizer}: в него попадает только
 * {@code architecture.asset.upserted.v1}; {@code architecture.asset.deleted.v1} превращается в tombstone
 * без нормализатора; {@code architecture.sync.snapshot-complete.v1} не проецируется, а передаётся в
 * {@link ReconciliationTrigger}; прочие типы уходят в {@code QUARANTINED} ({@code UNSUPPORTED_EVENT_TYPE}).
 *
 * <p>Инварианты:
 * <ul>
 *   <li>статусы проходят по одному шагу ({@code RECEIVED → VALIDATED → NORMALIZED → RESOLVED → PROJECTED});
 *       повтор уже применённого изменения с другим {@code eventId} и равной версией — {@code DUPLICATE},
 *       версия ниже — {@code IGNORED_OLD_VERSION}; оба решаются до записи и не меняют граф;</li>
 *   <li>{@code gid} выдаёт только {@link IdentityMapping}, и вызывается он до транзакции Neo4j, не внутри неё;</li>
 *   <li>checkpoint ({@value #CONSUMER}, ключ — {@code source}) сохраняется только после коммита Neo4j и
 *       перехода в конечный статус, курсором служит {@code eventId}; при {@code RETRYING}/{@code QUARANTINED}
 *       он не сдвигается;</li>
 *   <li>matching кандидатов на совпадение ({@link IdentityCandidates}) идёт после {@code resolveGids} и до
 *       транзакции Neo4j, не читает и не меняет карту {@code gids} и не вызывает
 *       {@code IdentityMapping.resolve/approve}: узлы не объединяются ни при каком score; tombstone
 *       убирает признаки ключа ({@code forget}); сбой хранилища — {@code RETRYING}/{@value #CANDIDATE_FAILED};</li>
 *   <li>ошибки не глушатся: нормализация и отказ графа переводят событие в {@code QUARANTINED}/{@code RETRYING}
 *       с кодом и причиной без значений из источника; неожиданные исключения, а также ошибки журнала
 *       пробрасываются вызывающему (событие остаётся в прежнем статусе, повтор безопасен).</li>
 * </ul>
 *
 * <p>Битые ссылки, которые normalizer не превратил в связи ({@code UnresolvedReference}), передаются проектору
 * командой {@link DeferRelation}: он сохраняет их как отложенные связи и достраивает при появлении конечной
 * точки (E1.14). Reconciliation (E1.6) их не лечит.
 */
public final class EventProcessor {

  /** Имя consumer в {@code consumer_checkpoint}. */
  public static final String CONSUMER = "projector";

  static final String CANDIDATE_FAILED = "IDENTITY_CANDIDATE_FAILED";
  static final String TYPE_ASSET_DELETED = "architecture.asset.deleted.v1";
  static final String TYPE_SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";
  static final String SCHEMA_ASSET_DELETED = "urn:corp:schema:asset-deleted:1";
  private static final String SOURCE_PREFIX = "urn:corp:";

  private static final Set<ProcessingStatus> FINISHED =
      Set.of(
          ProcessingStatus.PROJECTED,
          ProcessingStatus.SUPERSEDED,
          ProcessingStatus.DUPLICATE,
          ProcessingStatus.IGNORED_OLD_VERSION,
          ProcessingStatus.QUARANTINED);

  private final EventJournal journal;
  private final Normalizer normalizer;
  private final IdentityMapping identity;
  private final GraphProjection projection;
  private final ReconciliationTrigger reconciliation;
  private final IdentityCandidates candidates;

  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    this.candidates = Objects.requireNonNull(candidates, "candidates");
  }

  /**
   * Обрабатывает событие, уже добавленное в журнал ({@code EventJournal.append}).
   *
   * @param raw ссылка на raw payload события; её hash попадает в {@code SourceRecord}
   * @return итог; для события в конечном статусе или {@code QUARANTINED} возвращается текущий статус без изменений
   * @throws IllegalArgumentException если события нет в журнале
   */
  public ProcessingResult process(CanonicalEvent event, RawPayloadRef raw) {
    JournalEntry entry =
        journal
            .find(event.source(), event.id())
            .orElseThrow(() -> new IllegalArgumentException("event is not in the journal"));
    if (FINISHED.contains(entry.status())) {
      return new ProcessingResult(entry.status(), entry.errorCode(), null);
    }
    return switch (event.type()) {
      case CanonicalEvent.TYPE_ASSET_UPSERTED -> upsert(event, raw, entry);
      case TYPE_ASSET_DELETED -> delete(event, entry);
      case TYPE_SNAPSHOT_COMPLETE -> snapshotComplete(event, entry);
      default -> quarantine(event, "UNSUPPORTED_EVENT_TYPE", "event type is not supported");
    };
  }

  private ProcessingResult upsert(CanonicalEvent event, RawPayloadRef raw, JournalEntry entry) {
    SourceSystemCode source = sourceCode(event.source());
    if (source == null) {
      return quarantine(event, "UNKNOWN_SOURCE", "event source is not known");
    }
    AssetEventData data = event.data();
    SourceKey key = new SourceKey(source, data.sourceType(), data.sourceId());
    ProcessingResult early = decideBeforeWrite(event, entry, key, false);
    if (early != null) {
      return early;
    }
    advance(event, ProcessingStatus.VALIDATED);
    NormalizationResult normalized = normalizer.normalize(event, raw);
    if (normalized instanceof NormalizationResult.Quarantined q) {
      return quarantine(event, q.errorCode(), q.reason());
    }
    var result = (NormalizationResult.Normalized) normalized;
    List<GraphCommand> commands = new ArrayList<>(result.commands());
    // Связь с двумя неизвестными концами приходит дважды (по ссылке на каждый конец): откладываем один раз.
    result.unresolved().stream().map(UnresolvedReference::relation).distinct().map(DeferRelation::new).forEach(commands::add);
    advance(event, ProcessingStatus.NORMALIZED);
    Map<SourceKey, UUID> gids;
    try {
      gids = resolveGids(key, commands);
    } catch (ProjectionException e) {
      return retry(event, e.code(), e.getMessage());
    }
    try {
      recordCandidates(key, commands, gids);
    } catch (IdentityStoreException e) {
      // record идемпотентен: повтор безопасен. Значения признаков в причину не попадают.
      return retry(event, CANDIDATE_FAILED, "identity candidate store failed");
    }
    advance(event, ProcessingStatus.RESOLVED);
    return write(event, entry, key, commands, gids);
  }

  private ProcessingResult delete(CanonicalEvent event, JournalEntry entry) {
    SourceSystemCode source = sourceCode(event.source());
    if (source == null) {
      return quarantine(event, "UNKNOWN_SOURCE", "event source is not known");
    }
    if (!SCHEMA_ASSET_DELETED.equals(event.dataschema())) {
      return quarantine(event, "UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported");
    }
    AssetEventData data = event.data();
    SourceKey key = new SourceKey(source, data.sourceType(), data.sourceId());
    ProcessingResult early = decideBeforeWrite(event, entry, key, true);
    if (early != null) {
      return early;
    }
    advance(event, ProcessingStatus.VALIDATED);
    advance(event, ProcessingStatus.NORMALIZED);
    try {
      candidates.forget(key);
    } catch (IdentityStoreException e) {
      return retry(event, CANDIDATE_FAILED, "identity candidate store failed");
    }
    advance(event, ProcessingStatus.RESOLVED);
    return write(event, entry, key, List.of(new TombstoneSourceRecord(key, event.time())), Map.of());
  }

  private ProcessingResult snapshotComplete(CanonicalEvent event, JournalEntry entry) {
    advance(event, ProcessingStatus.VALIDATED);
    advance(event, ProcessingStatus.NORMALIZED);
    advance(event, ProcessingStatus.RESOLVED);
    try {
      reconciliation.snapshotComplete(
          event.source(), event.data().sourceId(), event.id(), objectCount(event));
    } catch (RuntimeException e) {
      return retry(event, "RECONCILIATION_TRIGGER_FAILED", e.getClass().getSimpleName());
    }
    return finish(event, ProcessingStatus.PROJECTED, null);
  }

  /** Повтор или устаревшее изменение отсекается до записи, пока событие ещё {@code RECEIVED}. */
  private ProcessingResult decideBeforeWrite(
      CanonicalEvent event, JournalEntry entry, SourceKey key, boolean tombstone) {
    if (entry.status() != ProcessingStatus.RECEIVED) {
      return null;
    }
    Optional<AppliedRecord> applied = projection.applied(key);
    if (applied.isEmpty()) {
      return null;
    }
    var incoming = event.data().sourceVersion();
    VersionDecision decision =
        tombstone
            ? VersionDecision.forTombstone(incoming, applied.get().version(), applied.get().active())
            : VersionDecision.forUpsert(incoming, applied.get().version());
    return switch (decision) {
      case APPLY -> null;
      case SAME -> finish(event, ProcessingStatus.DUPLICATE, null);
      case OLD -> finish(event, ProcessingStatus.IGNORED_OLD_VERSION, null);
    };
  }

  private ProcessingResult write(
      CanonicalEvent event,
      JournalEntry entry,
      SourceKey key,
      List<GraphCommand> commands,
      Map<SourceKey, UUID> gids) {
    ProjectionResult result;
    try {
      result =
          projection.project(
              new ProjectionRequest(
                  key, event.data().sourceVersion(), event.time(), entry.syncRunId(), gids, commands));
    } catch (ProjectionException e) {
      return retry(event, e.code(), e.getMessage());
    } catch (IllegalArgumentException e) {
      return quarantine(event, "INVALID_COMMANDS", "commands violate projection invariants");
    } catch (TransientException | ServiceUnavailableException | SessionExpiredException e) {
      return retry(event, "GRAPH_UNAVAILABLE", e.getClass().getSimpleName());
    } catch (ClientException e) {
      // В карантин только отказ по самому запросу; безопасность и гонка MERGE (constraint) — не вина события.
      if (e.code().startsWith("Neo.ClientError.Statement.") || e.code().startsWith("Neo.ClientError.Request.")) {
        return quarantine(event, "GRAPH_REJECTED", e.code());
      }
      return retry(event, "GRAPH_CLIENT_ERROR", e.code());
    } catch (Neo4jException e) {
      return retry(event, "GRAPH_ERROR", e.getClass().getSimpleName());
    }
    ProcessingStatus status =
        result.outcome() == ProjectionOutcome.IGNORED_OLD_VERSION
            ? ProcessingStatus.IGNORED_OLD_VERSION
            : ProcessingStatus.PROJECTED;
    return finish(event, status, result);
  }

  /**
   * Передаёт признаки узла самого события в {@link IdentityCandidates}; вне транзакции Neo4j. Карта
   * {@code gids} только читается: matching не создаёт и не меняет {@code gid}.
   */
  private void recordCandidates(SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids) {
    FeatureExtractor.labelOf(key, commands)
        .ifPresent(label -> candidates.record(key, label, FeatureExtractor.extract(key, commands, Map.copyOf(gids))));
  }

  /** {@code gid} всех ключей команд; вызывается до транзакции Neo4j. */
  private Map<SourceKey, UUID> resolveGids(SourceKey key, List<GraphCommand> commands) {
    Set<SourceKey> endpoints = new LinkedHashSet<>();
    for (GraphCommand command : commands) {
      switch (command) {
        case UpsertRelation r -> {
          endpoints.add(r.from());
          endpoints.add(r.to());
        }
        case CloseAssertion c -> {
          endpoints.add(c.from());
          endpoints.add(c.to());
        }
        // Отложенная связь gid не требует: неизвестный конец берётся из графа при достраивании.
        case DeferRelation d -> {}
        case UpsertNode u -> {}
        case TombstoneSourceRecord t -> {}
      }
    }
    Map<SourceKey, UUID> gids = new LinkedHashMap<>();
    gids.put(key, identity.resolve(key));
    for (SourceKey endpoint : endpoints) {
      if (!gids.containsKey(endpoint)) {
        // Чужой конец связи gid не создаёт: нет mapping — запись ещё не приходила.
        gids.put(
            endpoint,
            identity
                .find(endpoint)
                .orElseThrow(
                    () -> new ProjectionException(ProjectionException.UNRESOLVED_ENDPOINT, "relation endpoint has no gid")));
      }
    }
    return gids;
  }

  /** Двигает статус к {@code target} по одному шагу; из {@code RETRYING} допустим прыжок в любой этап. */
  private void advance(CanonicalEvent event, ProcessingStatus target) {
    ProcessingStatus current = journal.find(event.source(), event.id()).orElseThrow().status();
    if (current.ordinal() >= target.ordinal() && current != ProcessingStatus.RETRYING) {
      return;
    }
    journal.transition(event.source(), event.id(), target, null, null);
  }

  private ProcessingResult finish(CanonicalEvent event, ProcessingStatus status, ProjectionResult projected) {
    journal.transition(event.source(), event.id(), status, null, null);
    // Checkpoint только после коммита Neo4j и перехода в конечный статус.
    journal.saveCheckpoint(CONSUMER, event.source(), event.id());
    return new ProcessingResult(status, null, projected);
  }

  private ProcessingResult retry(CanonicalEvent event, String code, String reason) {
    journal.transition(event.source(), event.id(), ProcessingStatus.RETRYING, code, reason);
    return new ProcessingResult(ProcessingStatus.RETRYING, code, null);
  }

  private ProcessingResult quarantine(CanonicalEvent event, String code, String reason) {
    journal.toDlq(event.source(), event.id(), code, reason);
    return new ProcessingResult(ProcessingStatus.QUARANTINED, code, null);
  }

  private static long objectCount(CanonicalEvent event) {
    return event.data().payload().get("objectCount") instanceof Number n ? n.longValue() : 0;
  }

  /** Код системы по {@code source} события ({@code urn:corp:eam} → {@code EAM}); {@code null} для неизвестного. */
  static SourceSystemCode sourceCode(String urn) {
    if (!urn.startsWith(SOURCE_PREFIX)) {
      return null;
    }
    return switch (urn.substring(SOURCE_PREFIX.length())) {
      case "eam" -> SourceSystemCode.EAM;
      case "scm" -> SourceSystemCode.SCM;
      case "cmdb" -> SourceSystemCode.CMDB;
      case "deploymap" -> SourceSystemCode.DEPLOYMAP;
      default -> null;
    };
  }
}
