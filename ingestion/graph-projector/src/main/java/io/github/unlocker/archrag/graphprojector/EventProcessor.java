package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.DeferRelation;
import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Solution;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.provenance.SyncRunStatus;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
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
import io.github.unlocker.archrag.identityresolution.SourceConflicts;
import io.github.unlocker.archrag.normalizer.DeploymentLookup;
import io.github.unlocker.archrag.normalizer.NormalizationResult;
import io.github.unlocker.archrag.normalizer.Normalizer;
import io.github.unlocker.archrag.normalizer.UnresolvedReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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
 *   <li>конфликты источников ({@link SourceConflicts}) считаются после {@code resolveGids} и до транзакции Neo4j:
 *       upsert заменяет утверждения свойств записи, tombstone их снимает; открытые конфликты {@code gid}
 *       передаются проектору как {@link ConflictMarkers}; запись только со связью конфликтов не меняет; сбой
 *       хранилища — {@code RETRYING}/{@value #CONFLICT_FAILED}. Canonical-значение узла от конфликта не зависит;</li>
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
  static final String CONFLICT_FAILED = "SOURCE_CONFLICT_FAILED";
  /** Свойство, несущее ссылку развёртывания на систему EAM; имя {@code RELATION}-конфликта. */
  static final String SYSTEM_PROPERTY = "eamSystemId";
  /** Свойство узла, несущее платформу ИТ-системы и решения; имя {@code RELATION}-конфликта (ADR 0031). */
  static final String PLATFORM_PROPERTY = "platformId";
  private static final String COMPLETENESS_KEY = "_completeness";
  private static final String PARTIAL = "PARTIAL";
  private static final String IT_SYSTEM_TYPE = "IT_SYSTEM";
  private static final String SOLUTION_TYPE = "SOLUTION";

  /** Код карантина для события, исчерпавшего лимит повторов. */
  public static final String RETRY_LIMIT_EXCEEDED = "RETRY_LIMIT_EXCEEDED";
  /** Лимит повторов по умолчанию; в сервисе задаётся {@code archrag.dispatcher.max-attempts}. */
  public static final int DEFAULT_MAX_ATTEMPTS = 10;

  private static final Set<ProcessingStatus> FINISHED =
      Set.of(
          ProcessingStatus.PROJECTED,
          ProcessingStatus.DUPLICATE,
          ProcessingStatus.IGNORED_OLD_VERSION,
          ProcessingStatus.QUARANTINED);

  private final EventJournal journal;
  private final Normalizer normalizer;
  private final IdentityMapping identity;
  private final GraphProjection projection;
  private final ReconciliationTrigger reconciliation;
  private final IdentityCandidates candidates;
  private final SourceConflicts conflicts;
  private final DeploymentLookup lookup;
  private final EamLookup eamLookup;
  private final NormalizationWarnings warnings;
  private final SyncRunClosing syncRuns;
  private final int maxAttempts;

  /** Без чтения графа для проверки системы EAM из chart: такие конфликты не открываются. */
  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts) {
    this(journal, normalizer, identity, projection, reconciliation, candidates, conflicts, DeploymentLookup.NONE);
  }

  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts,
      DeploymentLookup lookup) {
    this(journal, normalizer, identity, projection, reconciliation, candidates, conflicts, lookup, EamLookup.NONE);
  }

  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts,
      DeploymentLookup lookup,
      EamLookup eamLookup) {
    this(journal, normalizer, identity, projection, reconciliation, candidates, conflicts, lookup, eamLookup, NormalizationWarnings.NONE);
  }

  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts,
      DeploymentLookup lookup,
      EamLookup eamLookup,
      NormalizationWarnings warnings) {
    this(journal, normalizer, identity, projection, reconciliation, candidates, conflicts, lookup, eamLookup, warnings,
        DEFAULT_MAX_ATTEMPTS);
  }

  /** @param maxAttempts сколько раз событие может уйти в {@code RETRYING}; после этого оно попадает в карантин */
  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts,
      DeploymentLookup lookup,
      EamLookup eamLookup,
      NormalizationWarnings warnings,
      int maxAttempts) {
    this(journal, normalizer, identity, projection, reconciliation, candidates, conflicts, lookup, eamLookup, warnings,
        maxAttempts, SyncRunClosing.NONE);
  }

  /**
   * @param maxAttempts сколько раз событие может уйти в {@code RETRYING}; после этого оно попадает в карантин
   * @param syncRuns закрытие {@code SyncRun} по итогу маркера {@code snapshot-complete}
   */
  public EventProcessor(
      EventJournal journal,
      Normalizer normalizer,
      IdentityMapping identity,
      GraphProjection projection,
      ReconciliationTrigger reconciliation,
      IdentityCandidates candidates,
      SourceConflicts conflicts,
      DeploymentLookup lookup,
      EamLookup eamLookup,
      NormalizationWarnings warnings,
      int maxAttempts,
      SyncRunClosing syncRuns) {
    this.syncRuns = Objects.requireNonNull(syncRuns, "syncRuns");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be positive");
    }
    this.maxAttempts = maxAttempts;
    this.warnings = Objects.requireNonNull(warnings, "warnings");
    this.lookup = Objects.requireNonNull(lookup, "lookup");
    this.eamLookup = Objects.requireNonNull(eamLookup, "eamLookup");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
    this.candidates = Objects.requireNonNull(candidates, "candidates");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
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
      case CanonicalEvent.TYPE_ASSET_DELETED -> delete(event, entry);
      case CanonicalEvent.TYPE_SNAPSHOT_COMPLETE -> snapshotComplete(event, entry);
      default -> quarantine(event, "UNSUPPORTED_EVENT_TYPE", "event type is not supported");
    };
  }

  /**
   * Заново проецирует применённую версию записи на её новый {@code gid} (запись перенесена
   * подтверждением кандидата, {@code IdentityMapping} уже отдаёт {@code gid} выжившего). Журнальный статус события и
   * checkpoint не трогаются: исходное событие давно в конечном статусе. Проверка версии обходится
   * ({@link Relocation}), утверждения свойств и конфликты нового {@code gid} пересчитываются как для обычного
   * события; кандидаты не пересчитываются. Ошибки не глушатся: ничего не откладывается в {@code RETRYING}, сбой
   * уходит вызывающему (админская операция фиксирует {@code FAILED}).
   *
   * @param event событие из raw: тип {@code architecture.asset.upserted.v1}
   * @param fromGid прежний {@code gid} записи
   * @param retireSource на прежнем {@code gid} ключей не осталось: его узел закрывается
   * @param at момент закрытия прежних узла и связей
   * @throws ProjectionException если нормализация отвергла событие или конец связи не найден
   * @throws IdentityStoreException при отказе хранилища
   */
  public ProjectionResult reproject(
      CanonicalEvent event, RawPayloadRef raw, UUID fromGid, boolean retireSource, Instant at) {
    SourceSystemCode source = sourceCode(event.source());
    if (source == null || !CanonicalEvent.TYPE_ASSET_UPSERTED.equals(event.type())) {
      throw new IllegalArgumentException("only an upserted event of a known source can be reprojected");
    }
    AssetEventData data = event.data();
    SourceKey key = new SourceKey(source, data.sourceType(), data.sourceId());
    NormalizationResult normalized = normalizer.normalize(event, raw);
    if (normalized instanceof NormalizationResult.Quarantined q) {
      throw new ProjectionException(q.errorCode(), q.reason());
    }
    var result = (NormalizationResult.Normalized) normalized;
    List<GraphCommand> commands = new ArrayList<>(result.commands());
    result.unresolved().stream().map(UnresolvedReference::relation).distinct().map(DeferRelation::new).forEach(commands::add);
    Map<SourceKey, UUID> gids = resolveGids(key, commands);
    ConflictMarkers markers = assertProperties(key, commands, gids);
    markers = assertSystemRelation(key, commands, gids, markers);
    markers = assertSystemPlatform(key, commands, gids, markers);
    Relocation relocation = new Relocation(fromGid, gids.get(key), retireSource, at);
    ProjectionResult projected =
        projection.project(
            new ProjectionRequest(
                key, data.sourceVersion(), event.time(), null, gids, commands, markers,
                PARTIAL.equals(data.payload().get(COMPLETENESS_KEY)), relocation));
    recheckServiceDeployments(key, commands);
    recheckSolutionSystems(key, commands);
    return projected;
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
    if (!result.warnings().isEmpty()) {
      warnings.record(source, result.warnings());
    }
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
    ProcessingResult stale = ignoreIfOlderOnRetry(event, entry, key, false);
    if (stale != null) {
      return stale;
    }
    ConflictMarkers markers;
    try {
      markers = assertProperties(key, commands, gids);
      markers = assertSystemRelation(key, commands, gids, markers);
      markers = assertSystemPlatform(key, commands, gids, markers);
    } catch (IdentityStoreException e) {
      // Замена утверждений идемпотентна: повтор безопасен. Значения свойств в причину не попадают.
      return retry(event, CONFLICT_FAILED, "source conflict store failed");
    }
    advance(event, ProcessingStatus.RESOLVED);
    return write(event, entry, key, commands, gids, markers);
  }

  private ProcessingResult delete(CanonicalEvent event, JournalEntry entry) {
    SourceSystemCode source = sourceCode(event.source());
    if (source == null) {
      return quarantine(event, "UNKNOWN_SOURCE", "event source is not known");
    }
    if (!CanonicalEvent.SCHEMA_DELETED.equals(event.dataschema())) {
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
    ProcessingResult stale = ignoreIfOlderOnRetry(event, entry, key, true);
    if (stale != null) {
      return stale;
    }
    ConflictMarkers markers;
    try {
      markers = retractProperties(key);
    } catch (IdentityStoreException e) {
      return retry(event, CONFLICT_FAILED, "source conflict store failed");
    }
    advance(event, ProcessingStatus.RESOLVED);
    return write(event, entry, key, List.of(new TombstoneSourceRecord(key, event.time())), Map.of(), markers);
  }

  private ProcessingResult snapshotComplete(CanonicalEvent event, JournalEntry entry) {
    // Без валидного objectCount полноту прогона проверить нечем: reconcile не запускается, маркер в карантин.
    OptionalLong objectCount = objectCount(event);
    if (objectCount.isEmpty()) {
      return quarantine(event, SnapshotRejectedException.MISSING_OBJECT_COUNT, "marker has no valid objectCount");
    }
    advance(event, ProcessingStatus.VALIDATED);
    advance(event, ProcessingStatus.NORMALIZED);
    advance(event, ProcessingStatus.RESOLVED);
    try {
      reconciliation.snapshotComplete(
          event.source(), event.data().sourceId(), event.id(), objectCount.getAsLong(), isPartial(event));
    } catch (SnapshotRejectedException e) {
      return quarantine(event, e.code(), e.getMessage());
    } catch (RuntimeException e) {
      return retry(event, "RECONCILIATION_TRIGGER_FAILED", e.getClass().getSimpleName());
    }
    // Прогон закрывается до перехода маркера в PROJECTED: сбой записи в Neo4j оставляет маркер на повтор.
    try {
      syncRuns.close(event.source(), event.data().sourceId(), event.id(), SyncRunStatus.SUCCEEDED);
    } catch (RuntimeException e) {
      return retry(event, "SYNC_RUN_CLOSE_FAILED", e.getClass().getSimpleName());
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

  /**
   * Повтор {@code RETRYING}-события мог пережить более новую версию: {@code decideBeforeWrite} его не проверяет
   * (статус не {@code RECEIVED}), а запись в PostgreSQL затёрла бы свежие утверждения устаревшими. Старая версия
   * отсекается до записи в PG; равная версия идёт дальше: запись идемпотентна, проектор вернёт {@code NOOP}.
   */
  private ProcessingResult ignoreIfOlderOnRetry(
      CanonicalEvent event, JournalEntry entry, SourceKey key, boolean tombstone) {
    if (entry.status() == ProcessingStatus.RECEIVED) {
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
    return decision == VersionDecision.OLD ? finish(event, ProcessingStatus.IGNORED_OLD_VERSION, null) : null;
  }

  private ProcessingResult write(
      CanonicalEvent event,
      JournalEntry entry,
      SourceKey key,
      List<GraphCommand> commands,
      Map<SourceKey, UUID> gids,
      ConflictMarkers markers) {
    ProjectionResult result;
    try {
      result =
          projection.project(
              new ProjectionRequest(
                  key, event.data().sourceVersion(), event.time(), entry.syncRunId(), gids, commands, markers,
                  PARTIAL.equals(event.data().payload().get(COMPLETENESS_KEY))));
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
    if (result.outcome() != ProjectionOutcome.IGNORED_OLD_VERSION) {
      try {
        recheckServiceDeployments(key, commands);
        recheckSolutionSystems(key, commands);
      } catch (IdentityStoreException e) {
        // Граф уже записан, проверка идемпотентна: повтор события безопасен (проектор вернёт NOOP).
        return retry(event, CONFLICT_FAILED, "source conflict store failed");
      }
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

  /**
   * Заменяет утверждения свойств узла самого события и возвращает маркеры открытых конфликтов его {@code gid};
   * вне транзакции Neo4j. Запись без {@code UpsertNode} утверждений не меняет.
   */
  private ConflictMarkers assertProperties(SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids) {
    for (GraphCommand command : commands) {
      if (command instanceof UpsertNode upsert) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeProperties.of(upsert.data()).forEach((name, value) -> values.put(name, String.valueOf(value)));
        UUID gid = gids.get(key);
        return ConflictMarkers.of(gid, conflicts.assertProperties(key, gid, upsert.data().label(), values));
      }
    }
    return ConflictMarkers.none();
  }

  /**
   * Сверяет {@code Deployment.eamSystemId} из chart с системой EAM сервиса развёртывания (из {@code HAS_DEPLOYMENT}
   * этого же события и графа); вне транзакции Neo4j. Граф не меняется: расхождение только фиксируется. Без
   * {@code eamSystemId} (или без известной системы сервиса) открытые конфликты записи закрываются.
   *
   * @param markers результат {@code assertProperties}; заменяется полным набором конфликтов {@code gid}
   */
  private ConflictMarkers assertSystemRelation(
      SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids, ConflictMarkers markers) {
    if (key.source() != SourceSystemCode.DEPLOYMAP) {
      return markers;
    }
    for (GraphCommand command : commands) {
      if (command instanceof UpsertNode upsert && upsert.data() instanceof Deployment deployment) {
        UUID gid = gids.get(key);
        Optional<SourceKey> master = Optional.empty();
        if (deployment.eamSystemId() != null) {
          master =
              commands.stream()
                  .filter(
                      c ->
                          c instanceof UpsertRelation r
                              && r.type() == RelationType.HAS_DEPLOYMENT
                              && r.to().equals(key)
                              && r.from().source() == SourceSystemCode.SCM
                              && "SERVICE".equals(r.from().sourceType()))
                  .map(c -> ((UpsertRelation) c).from())
                  .findFirst()
                  .flatMap(lookup::eamSystemOfService);
        }
        String chartValue = deployment.eamSystemId() == null ? "" : String.valueOf(deployment.eamSystemId());
        return ConflictMarkers.of(
            gid,
            conflicts.assertRelation(
                key, gid, SYSTEM_PROPERTY, chartValue, master, master.map(SourceKey::sourceId).orElse("")));
      }
    }
    return markers;
  }

  /**
   * После записи SCM-сервиса перепроверяет его развёртывания с {@code eamSystemId}: система сервиса могла
   * появиться или смениться, и порядок прихода SCM и DeployMap на результат не влияет. Маркеры {@code ASSERTS}
   * развёртываний здесь не пишутся (запрос проектора относится к {@code gid} одной записи): они обновятся при
   * следующем событии развёртывания, а сам конфликт виден в хранилище сразу.
   */
  private void recheckServiceDeployments(SourceKey key, List<GraphCommand> commands) {
    boolean isService = key.source() == SourceSystemCode.SCM && "SERVICE".equals(key.sourceType());
    if (!isService || commands.stream().anyMatch(TombstoneSourceRecord.class::isInstance)) {
      return;
    }
    List<DeploymentLookup.ChartDeployment> deployments = lookup.chartDeploymentsOfService(key);
    if (deployments.isEmpty()) {
      return;
    }
    Optional<SourceKey> master = lookup.eamSystemOfService(key);
    for (DeploymentLookup.ChartDeployment deployment : deployments) {
      identity
          .find(deployment.key())
          .ifPresent(
              gid ->
                  conflicts.assertRelation(
                      deployment.key(),
                      gid,
                      SYSTEM_PROPERTY,
                      String.valueOf(deployment.eamSystemId()),
                      master,
                      master.map(SourceKey::sourceId).orElse("")));
    }
  }

  /**
   * Сверяет {@code ITSystem.platformId} события EAM с {@code platformId} её решения (ключ решения — из
   * {@code USES_SOLUTION} этого же события, в том числе отложенной); вне транзакции Neo4j. Граф не меняется:
   * расхождение только фиксируется. Без {@code platformId} системы, без известного решения или без его
   * {@code platformId} открытые конфликты записи закрываются.
   *
   * @param markers предыдущий результат; заменяется полным набором конфликтов {@code gid}
   */
  private ConflictMarkers assertSystemPlatform(
      SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids, ConflictMarkers markers) {
    if (key.source() != SourceSystemCode.EAM || !IT_SYSTEM_TYPE.equals(key.sourceType())) {
      return markers;
    }
    for (GraphCommand command : commands) {
      if (command instanceof UpsertNode upsert && upsert.data() instanceof ITSystem system) {
        UUID gid = gids.get(key);
        Optional<EamLookup.SolutionPlatform> solution =
            solutionOf(key, commands).flatMap(eamLookup::solutionPlatform).filter(s -> s.platformId() != null);
        Optional<SourceKey> master =
            system.platformId() == null ? Optional.empty() : solution.map(EamLookup.SolutionPlatform::solution);
        String systemValue = system.platformId() == null ? "" : String.valueOf(system.platformId());
        String masterValue = master.isPresent() ? String.valueOf(solution.get().platformId()) : "";
        return ConflictMarkers.of(gid, conflicts.assertRelation(key, gid, PLATFORM_PROPERTY, systemValue, master, masterValue));
      }
    }
    return markers;
  }

  /** Ключ решения системы из {@code USES_SOLUTION} команд события: готовой или отложенной связи. */
  private static Optional<SourceKey> solutionOf(SourceKey system, List<GraphCommand> commands) {
    return commands.stream()
        .map(
            c ->
                switch (c) {
                  case UpsertRelation r -> r;
                  case DeferRelation d -> d.relation();
                  default -> null;
                })
        .filter(r -> r != null && r.type() == RelationType.USES_SOLUTION && r.from().equals(system))
        .map(UpsertRelation::to)
        .findFirst();
  }

  /**
   * После записи решения EAM перепроверяет {@code platformId} его систем: решение могло прийти позже системы или
   * сменить платформу, порядок прихода на результат не влияет. Маркеры {@code ASSERTS} систем здесь не пишутся
   * (ограничение 1 ADR 0030): они обновятся при следующем событии системы, а сам конфликт виден в хранилище сразу.
   */
  private void recheckSolutionSystems(SourceKey key, List<GraphCommand> commands) {
    boolean isSolution = key.source() == SourceSystemCode.EAM && SOLUTION_TYPE.equals(key.sourceType());
    if (!isSolution || commands.stream().anyMatch(TombstoneSourceRecord.class::isInstance)) {
      return;
    }
    Long platformId =
        commands.stream()
            .filter(c -> c instanceof UpsertNode u && u.data() instanceof Solution)
            .map(c -> ((Solution) ((UpsertNode) c).data()).platformId())
            .filter(Objects::nonNull)
            .findFirst()
            // null из источника не затирает известное: платформа решения в графе осталась прежней.
            .orElseGet(() -> eamLookup.solutionPlatform(key).map(EamLookup.SolutionPlatform::platformId).orElse(null));
    for (EamLookup.SystemPlatform system : eamLookup.systemsOfSolution(key)) {
      Optional<SourceKey> master = platformId == null || system.platformId() == null ? Optional.empty() : Optional.of(key);
      identity
          .find(system.system())
          .ifPresent(
              gid ->
                  conflicts.assertRelation(
                      system.system(),
                      gid,
                      PLATFORM_PROPERTY,
                      system.platformId() == null ? "" : String.valueOf(system.platformId()),
                      master,
                      master.isPresent() ? String.valueOf(platformId) : ""));
    }
  }

  /** Снимает утверждения удалённой записи; без {@code gid} (запись не приходила) маркеры не меняются. */
  private ConflictMarkers retractProperties(SourceKey key) {
    return identity.find(key).map(gid -> ConflictMarkers.of(gid, conflicts.retract(key, gid))).orElseGet(ConflictMarkers::none);
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
    JournalEntry entry = journal.transition(event.source(), event.id(), ProcessingStatus.RETRYING, code, reason);
    if (entry.attempts() >= maxAttempts) {
      // Лимит исчерпан: RETRYING -> QUARANTINED, в причину идёт исходный код сбоя и его текст.
      return quarantine(event, RETRY_LIMIT_EXCEEDED, code + ": " + reason);
    }
    return new ProcessingResult(ProcessingStatus.RETRYING, code, null);
  }

  private ProcessingResult quarantine(CanonicalEvent event, String code, String reason) {
    if (CanonicalEvent.TYPE_SNAPSHOT_COMPLETE.equals(event.type())) {
      // Единая точка: любой карантин маркера закрывает прогон как FAILED. Сбой записи уходит наружу до DLQ,
      // маркер остаётся необработанным и придёт на повтор.
      syncRuns.close(event.source(), event.data().sourceId(), event.id(), SyncRunStatus.FAILED);
    }
    journal.toDlq(event.source(), event.id(), code, reason);
    return new ProcessingResult(ProcessingStatus.QUARANTINED, code, null);
  }

  /** {@code objectCount} маркера; пусто, если поля нет, оно не целое число или отрицательное. */
  private static OptionalLong objectCount(CanonicalEvent event) {
    Object value = event.data().payload().get("objectCount");
    boolean integral = value instanceof Long || value instanceof Integer;
    return integral && ((Number) value).longValue() >= 0
        ? OptionalLong.of(((Number) value).longValue())
        : OptionalLong.empty();
  }

  /** Маркер заявил полноту {@code PARTIAL}; отсутствие поля означает {@code COMPLETE} (формат до UNLOCKER-279). */
  private static boolean isPartial(CanonicalEvent event) {
    return PARTIAL.equals(event.data().payload().get("completeness"));
  }

  /** Код системы по {@code source} события ({@code urn:corp:eam} → {@code EAM}); {@code null} для неизвестного. */
  static SourceSystemCode sourceCode(String urn) {
    // MANUAL — источник ручной модерации, событий от него быть не может
    return SourceSystemCode.fromUrn(urn).filter(code -> code != SourceSystemCode.MANUAL).orElse(null);
  }
}
