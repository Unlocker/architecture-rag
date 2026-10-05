package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.identityresolution.SourceConflict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Маркеры конфликтов узла {@code gid}: записи-диссиденты и имена свойств, по которым они расходятся с мастером.
 *
 * <p>Инварианты: это <em>полный</em> набор открытых конфликтов {@code gid}, а не дельта: проектор ставит
 * {@code conflicts} на ребро {@code ASSERTS} каждой записи из карты и снимает со всех остальных. Значения
 * свойств сюда не попадают, только имена; {@link #none()} означает «без изменений» и ничего не трогает.
 *
 * @param gid canonical-узел; {@code null} только у {@link #none()}
 * @param byRecord запись-диссидент → свойства (непустые списки)
 */
public record ConflictMarkers(UUID gid, Map<SourceKey, List<String>> byRecord) {

  private static final ConflictMarkers NONE = new ConflictMarkers(null, Map.of());

  public ConflictMarkers {
    if (gid == null && !byRecord.isEmpty()) {
      throw new IllegalArgumentException("markers require a gid");
    }
    Map<SourceKey, List<String>> copy = new LinkedHashMap<>();
    byRecord.forEach(
        (key, properties) -> {
          if (properties.isEmpty()) {
            throw new IllegalArgumentException("a marked record must have at least one property");
          }
          copy.put(key, List.copyOf(properties));
        });
    byRecord = Map.copyOf(copy);
  }

  /** Без изменений: маркеры графа не трогаются. */
  public static ConflictMarkers none() {
    return NONE;
  }

  /** Полный набор для {@code gid}; пустая карта означает «конфликтов нет, снять все маркеры». */
  public static ConflictMarkers of(UUID gid, Map<SourceKey, List<String>> byRecord) {
    return new ConflictMarkers(Objects.requireNonNull(gid, "gid"), byRecord);
  }

  /** Полный набор для {@code gid} из открытых конфликтов: запись-диссидент → имена свойств (значения не копируются). */
  public static ConflictMarkers of(UUID gid, List<SourceConflict> open) {
    Map<SourceKey, List<String>> byRecord = new LinkedHashMap<>();
    for (SourceConflict conflict : open) {
      byRecord.computeIfAbsent(conflict.dissent(), k -> new ArrayList<>()).add(conflict.property());
    }
    return of(gid, byRecord);
  }

  /** {@code true}, если маркеры не меняются. */
  public boolean isNone() {
    return gid == null;
  }
}
