package io.github.unlocker.archrag.graphquerycore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Единое правило «устарел» для MCP и консоли.
 *
 * <p>Инварианты: пустое или нераспознанное время — устарел; время старше {@code now - staleAfter} —
 * устарел, ровно на пороге ещё свежо; если ни один источник не активен — устарел.
 */
public final class Staleness {

  private final Duration staleAfter;
  private final Clock clock;

  public Staleness(StalenessProperties properties, Clock clock) {
    this.staleAfter = properties.staleAfter();
    this.clock = clock;
  }

  /** Порог устаревания, как он сконфигурирован. */
  public Duration staleAfter() {
    return staleAfter;
  }

  /**
   * Устарело ли время.
   *
   * @param timestamp ISO-8601 с зоной или {@code null}
   */
  public boolean isStale(String timestamp) {
    if (timestamp == null) {
      return true;
    }
    Instant seen;
    try {
      seen = OffsetDateTime.parse(timestamp).toInstant();
    } catch (DateTimeParseException e) {
      return true;
    }
    return seen.isBefore(clock.instant().minus(staleAfter));
  }

  /**
   * Устарел ли объект с источниками: время устарело или ни один источник не активен.
   *
   * @param timestamp ISO-8601 с зоной или {@code null}
   * @param anySourceActive есть ли хотя бы один активный источник
   */
  public boolean isStale(String timestamp, boolean anySourceActive) {
    return !anySourceActive || isStale(timestamp);
  }
}
