package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjection;
import io.github.unlocker.archrag.graphprojector.GraphProjection.AppliedRecord;
import io.github.unlocker.archrag.graphprojector.ProjectionException;
import io.github.unlocker.archrag.identityresolution.CandidateApproval;
import io.github.unlocker.archrag.identityresolution.CandidateApprovals;
import io.github.unlocker.archrag.identityresolution.CandidateRelocation;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Подтверждение кандидата на совпадение: перенос записи в PostgreSQL ({@link CandidateApprovals}) и сразу
 * перепроекция её последней применённой версии из raw на узел выжившего ({@link EventProcessor#reproject}).
 *
 * <p>Вызывается под {@link AdminLock} ({@link AdminOperations}). Шаги PostgreSQL и графа не атомарны: PostgreSQL
 * коммитится первым (gid берётся из {@code identity_mapping}), поэтому сбой перепроекции оставляет кандидата
 * {@code RESOLVED}, а граф до rebuild прежним; ошибка не глушится и попадает в аудит как {@code FAILED}. Raw
 * читается вне транзакции Neo4j. Журнальный статус событий и checkpoint не меняются.
 */
@Component
public class CandidateApprovalService {

  static final String NO_RAW_EVENT = "NO_RAW_EVENT";

  private final CandidateApprovals approvals;
  private final GraphProjection projection;
  private final JournalReader reader;
  private final RawPayloadStore rawStore;
  private final StoredEventReader events;
  private final EventProcessor processor;

  public CandidateApprovalService(
      CandidateApprovals approvals,
      GraphProjection projection,
      JournalReader reader,
      RawPayloadStore rawStore,
      StoredEventReader events,
      EventProcessor processor) {
    this.approvals = approvals;
    this.projection = projection;
    this.reader = reader;
    this.rawStore = rawStore;
    this.events = events;
    this.processor = processor;
  }

  /**
   * @throws io.github.unlocker.archrag.identityresolution.CandidateNotFoundException если для пары нет кандидата
   * @throws io.github.unlocker.archrag.identityresolution.CandidateNotOpenException если кандидат уже {@code RESOLVED}
   * @throws ReprojectionException если у применённой версии нет сохранённого raw или нормализация её отвергла
   */
  public CandidateApprovalResult approve(CandidateApproval approval) {
    CandidateRelocation relocation = approvals.approve(approval);
    if (!relocation.changesGid()) {
      return new CandidateApprovalResult(
          CandidateApprovalResult.Status.UNCHANGED, relocation.intoGid(), relocation.fromGid(), false);
    }
    SourceKey moved = relocation.moved();
    Optional<AppliedRecord> applied = projection.applied(moved);
    if (applied.isEmpty() || !applied.get().active()) {
      return result(CandidateApprovalResult.Status.REMAPPED, relocation, false);
    }
    StoredEvent stored =
        reader
            .projectedUpsert(
                "urn:corp:" + moved.source().name().toLowerCase(Locale.ROOT),
                moved.sourceType(),
                moved.sourceId(),
                applied.get().version())
            .orElseThrow(() -> new ReprojectionException(NO_RAW_EVENT, "no projected event with raw payload for the record"));
    RawPayloadRef ref = stored.entry().payloadRef();
    CanonicalEvent event = events.toEvent(stored, rawStore.get(ref));
    try {
      processor.reproject(event, ref, relocation.fromGid(), relocation.sourceGidEmpty(), Instant.now());
    } catch (ProjectionException e) {
      throw new ReprojectionException(e.code(), e.getMessage());
    }
    return result(CandidateApprovalResult.Status.REPROJECTED, relocation, relocation.sourceGidEmpty());
  }

  private static CandidateApprovalResult result(
      CandidateApprovalResult.Status status, CandidateRelocation relocation, boolean retired) {
    return new CandidateApprovalResult(status, relocation.intoGid(), relocation.fromGid(), retired);
  }
}
