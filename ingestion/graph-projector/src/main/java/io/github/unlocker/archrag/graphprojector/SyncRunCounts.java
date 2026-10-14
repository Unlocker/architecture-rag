package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Map;

/**
 * Счётчики закрытого прогона {@code SyncRun}; считаются по журналу, маркер {@code snapshot-complete} в них не входит.
 *
 * @param fetched все события прогона (в любом статусе)
 * @param applied события прогона в {@code PROJECTED}, {@code DUPLICATE}, {@code IGNORED_OLD_VERSION}
 * @param failed события прогона в {@code QUARANTINED} и {@code RETRYING}: не применены, и после закрытия прогона
 *     счётчики уже не обновятся
 */
public record SyncRunCounts(long fetched, long applied, long failed) {

  /**
   * Считает счётчики по числу событий прогона в каждом статусе. Значения присваиваются, а не накапливаются, поэтому
   * повторное закрытие даёт тот же результат.
   */
  public static SyncRunCounts of(Map<ProcessingStatus, Long> byStatus) {
    long fetched = byStatus.values().stream().mapToLong(Long::longValue).sum();
    long applied =
        count(byStatus, ProcessingStatus.PROJECTED)
            + count(byStatus, ProcessingStatus.DUPLICATE)
            + count(byStatus, ProcessingStatus.IGNORED_OLD_VERSION);
    long failed = count(byStatus, ProcessingStatus.QUARANTINED) + count(byStatus, ProcessingStatus.RETRYING);
    return new SyncRunCounts(fetched, applied, failed);
  }

  private static long count(Map<ProcessingStatus, Long> byStatus, ProcessingStatus status) {
    return byStatus.getOrDefault(status, 0L);
  }
}
