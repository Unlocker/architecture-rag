package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;
import java.util.Objects;

/**
 * Позиция строки журнала в порядке {@code (receivedAt, source, eventId)}: курсор keyset-пагинации
 * {@link JournalReader}. {@code eventId} уникален только в пределах {@code source}, поэтому порядок по нему одному
 * не определён.
 */
public record JournalKey(Instant receivedAt, String source, String eventId) {

  public JournalKey {
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(eventId, "eventId");
  }

  /** Позиция записи журнала. */
  public static JournalKey of(JournalEntry entry) {
    return new JournalKey(entry.receivedAt(), entry.source(), entry.eventId());
  }
}
