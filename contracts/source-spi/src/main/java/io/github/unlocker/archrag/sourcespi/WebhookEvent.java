package io.github.unlocker.archrag.sourcespi;

import java.time.Instant;

/**
 * Уведомление webhook: сообщает, что объект изменился, но не несёт полного состояния. Состояние
 * адаптер читает через {@link SourceConnector#fetchById}.
 *
 * @param eventId идентификатор события, уникальный в пределах источника; повторная доставка
 *     сохраняет его
 * @param source код источника
 * @param sourceType тип объекта
 * @param sourceId идентификатор объекта
 * @param sourceVersion версия объекта на момент события
 * @param operation upsert или delete
 * @param occurredAt время события в источнике, UTC
 */
public record WebhookEvent(
    String eventId,
    String source,
    String sourceType,
    String sourceId,
    long sourceVersion,
    ChangeOperation operation,
    Instant occurredAt) {}
