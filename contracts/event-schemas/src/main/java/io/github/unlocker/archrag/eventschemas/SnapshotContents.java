package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;
import java.util.Set;

/**
 * Что записано в журнал за один прогон полного snapshot (события {@code snap:<syncRunId>:...}).
 *
 * @param objects объекты, которые источник отдал в этом прогоне, включая события в {@code DUPLICATE},
 *     {@code QUARANTINED} и {@code RETRYING}: объект существует в источнике, как бы ни прошла его проекция
 * @param eventCount число событий прогона (без маркера)
 * @param firstReceivedAt момент первой записи прогона или {@code null}, если событий нет
 */
public record SnapshotContents(Set<ObjectRef> objects, long eventCount, Instant firstReceivedAt) {

  public SnapshotContents {
    objects = Set.copyOf(objects);
  }
}
