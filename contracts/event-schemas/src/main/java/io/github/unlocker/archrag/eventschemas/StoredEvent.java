package io.github.unlocker.archrag.eventschemas;

import java.util.Objects;

/**
 * Строка журнала вместе с полями, которых нет в {@link JournalEntry}. Сам payload лежит только в raw storage
 * ({@link JournalEntry#payloadRef()}).
 *
 * @param type тип события, например {@code architecture.asset.upserted.v1}
 * @param subject объект события (может быть {@code null})
 */
public record StoredEvent(JournalEntry entry, String type, String subject) {

  public StoredEvent {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(type, "type");
  }
}
