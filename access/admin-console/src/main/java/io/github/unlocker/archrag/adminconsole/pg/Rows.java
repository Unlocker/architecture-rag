package io.github.unlocker.archrag.adminconsole.pg;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Формы ответов API журнала. Полей с payload нет намеренно: в PostgreSQL лежит только ключ (hash и ссылка на
 * объект S3), сам payload консоль не читает и не отдаёт.
 */
public final class Rows {

  private Rows() {}

  /** Запись {@code inbox_event}; {@code payloadRef} — ключ в S3, {@code contentHash} — hash raw. */
  public record Event(
      String source,
      String eventId,
      String type,
      String subject,
      String sourceType,
      String sourceId,
      String sourceVersion,
      String correlationId,
      String syncRunId,
      String schemaVersion,
      String status,
      int attempts,
      String errorCode,
      String payloadRef,
      String contentHash,
      Instant receivedAt,
      Instant updatedAt) {}

  /** Подробности события: причина ошибки и записи DLQ как единственная хранимая история. */
  public record EventDetail(Event event, String errorReason, java.util.List<DlqEntry> dlqEntries, String historyNote) {}

  /** Запись {@code dlq_entry}. */
  public record DlqEntry(
      long id,
      String source,
      String eventId,
      String errorCode,
      String reason,
      String payloadRef,
      Instant createdAt,
      Instant replayedAt) {}

  /** Запись {@code source_conflict}. */
  public record Conflict(
      String gid,
      String property,
      String dissentSource,
      String dissentType,
      String dissentId,
      String dissentValue,
      String masterSource,
      String masterType,
      String masterId,
      String masterValue,
      String status,
      Instant openedAt,
      Instant updatedAt,
      Instant resolvedAt) {}

  /** Запись {@code identity_candidate}; {@code matched} — массив {@code {feature, value}}. */
  public record Candidate(
      String leftSource,
      String leftType,
      String leftId,
      String rightSource,
      String rightType,
      String rightId,
      String labelFamily,
      JsonNode matched,
      double score,
      String status,
      Instant firstSeenAt,
      Instant updatedAt) {}

  /** Запись {@code admin_audit} без {@code request}; {@code result} длиннее лимита не отдаётся ({@code resultTruncated}). */
  public record Audit(
      long id,
      String operation,
      String actor,
      String status,
      String replayId,
      JsonNode result,
      boolean resultTruncated,
      String error,
      Instant startedAt,
      Instant finishedAt) {}

  /** Checkpoint consumer-а по источнику. */
  public record Checkpoint(String consumer, String cursor, Instant updatedAt) {}

  /** Последний прогон адаптера из графа; не записанные проектором поля — {@code null}. */
  public record SyncRunInfo(
      String runId,
      String startedAt,
      String endedAt,
      String status,
      Long fetched,
      Long applied,
      Long failed) {}

  /** Сводка по источнику. */
  public record SourceStatus(
      String source,
      java.util.List<Checkpoint> checkpoints,
      SyncRunInfo lastSyncRun,
      java.util.Map<String, Long> eventsByStatus,
      Instant lastProjectedAt,
      Long lagSeconds) {}
}
