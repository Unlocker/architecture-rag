package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Map;

/**
 * Счётчики закрытого прогона {@code SyncRun}.
 *
 * @param fetched сколько объектов заявил адаптер ({@code objectCount} маркера)
 * @param applied события прогона в {@code PROJECTED}, {@code DUPLICATE}, {@code IGNORED_OLD_VERSION}
 * @param failed события прогона в {@code QUARANTINED}
 */
public record SyncRunCounts(long fetched, long applied, long failed) {

  /**
   * Считает счётчики по числу событий прогона в каждом статусе (без маркера). Значения присваиваются, а не
   * накапливаются, поэтому повторное закрытие даёт тот же результат.
   */
  public static SyncRunCounts of(long fetched, Map<ProcessingStatus, Long> byStatus) {
    long applied =
        count(byStatus, ProcessingStatus.PROJECTED)
            + count(byStatus, ProcessingStatus.DUPLICATE)
            + count(byStatus, ProcessingStatus.IGNORED_OLD_VERSION);
    return new SyncRunCounts(fetched, applied, count(byStatus, ProcessingStatus.QUARANTINED));
  }

  private static long count(Map<ProcessingStatus, Long> byStatus, ProcessingStatus status) {
    return byStatus.getOrDefault(status, 0L);
  }
}
