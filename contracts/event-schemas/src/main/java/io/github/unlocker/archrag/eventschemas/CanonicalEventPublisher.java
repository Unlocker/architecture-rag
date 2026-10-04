package io.github.unlocker.archrag.eventschemas;

/**
 * Публикация канонических событий потребителям (normalizer/projector). Граница варианта A/B:
 * реализация поверх {@link EventJournal} или Kafka не меняет адаптеры.
 */
public interface CanonicalEventPublisher {

  /**
   * Публикует событие at-least-once; повтор с тем же {@code (source, id)} безопасен.
   *
   * @return {@code true}, если событие принято впервые, {@code false}, если это дубликат
   */
  boolean publish(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId);
}
