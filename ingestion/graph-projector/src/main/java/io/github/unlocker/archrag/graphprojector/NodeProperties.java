package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Свойства узла и связи в виде, который принимает драйвер Neo4j.
 *
 * <p>Инварианты: значения {@code null} в результат не попадают (неполная запись не затирает известное,
 * ловушка №5); {@code Instant} превращается в UTC {@code OffsetDateTime} (в Neo4j это {@code datetime});
 * enum — в имя.
 */
final class NodeProperties {

  private NodeProperties() {}

  /** Непустые компоненты record'а {@code data}. */
  static Map<String, Object> of(NodeData data) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (RecordComponent component : data.getClass().getRecordComponents()) {
      Object value;
      try {
        value = component.getAccessor().invoke(data);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException("cannot read node property " + component.getName(), e);
      }
      if (value != null) {
        result.put(component.getName(), value(value));
      }
    }
    return result;
  }

  /** То же для свойств связи; ключи и значения уже провалидированы {@code UpsertRelation}. */
  static Map<String, Object> of(Map<String, Object> properties) {
    Map<String, Object> result = new LinkedHashMap<>();
    properties.forEach((key, value) -> result.put(key, value(value)));
    return result;
  }

  static Object value(Object value) {
    return switch (value) {
      case Instant instant -> utc(instant);
      case Enum<?> e -> e.name();
      case List<?> list -> list.stream().map(NodeProperties::value).toList();
      default -> value;
    };
  }

  static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
