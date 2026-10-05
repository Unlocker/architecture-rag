package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;

/**
 * Запись журнала: метаданные события, его состояние и ссылка на raw payload.
 * Идентична для {@code (source, eventId)} на всём жизненном цикле; время — UTC.
 *
 * @param syncRunId прогон синхронизации (может быть {@code null})
 * @param errorCode код ошибки для {@code QUARANTINED}/{@code RETRYING}, иначе {@code null}
 * @param errorReason краткая причина без содержимого источника, иначе {@code null}
 * @param attempts число попыток обработки
 */
public record JournalEntry(
    String source,
    String eventId,
    String correlationId,
    String syncRunId,
    String sourceType,
    String sourceId,
    SourceVersion sourceVersion,
    String schemaVersion,
    ProcessingStatus status,
    String errorCode,
    String errorReason,
    int attempts,
    RawPayloadRef payloadRef,
    Instant receivedAt,
    Instant updatedAt) {}
