package io.github.unlocker.archrag.sourcestubs;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Формат ответов реального EAM API ({@code docs/eam-api.yaml}, «EAM Tool API» v0.4): разбор и проверка
 * {@code ArchObject {id:int, ver:int, datetime, attrs}}. Список объекта — голый массив без {@code total}/{@code next}.
 * Общий для заглушки {@link EamApiStub} и контрактных тестов, чтобы fixtures и заглушка проверялись одним кодом.
 */
public final class EamApiFormat {

  private EamApiFormat() {}

  /** Разобрать произвольный JSON ({@code Map}, {@code List}, {@code String}, {@code Long}, {@code Double}, ...). */
  public static Object parse(String json) {
    return Json.parse(json);
  }

  /**
   * Разобрать тело списка и проверить каждый элемент как {@code ArchObject}: целые {@code id} и {@code ver},
   * {@code attrs} — объект, {@code datetime} — ISO-8601, если задан.
   *
   * @throws IllegalArgumentException если корень не массив или элемент не соответствует схеме
   */
  public static List<Map<String, Object>> parseArchObjects(String json) {
    if (!(parse(json) instanceof List<?> list)) {
      throw new IllegalArgumentException("list response must be a bare JSON array");
    }
    return list.stream().map(EamApiFormat::checkArchObject).toList();
  }

  /** Проверить один {@code ArchObject}; возвращает его же. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> checkArchObject(Object element) {
    if (!(element instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("ArchObject must be a JSON object: " + element);
    }
    Map<String, Object> o = (Map<String, Object>) m;
    for (String required : List.of("id", "ver", "attrs")) {
      if (!o.containsKey(required)) {
        throw new IllegalArgumentException("ArchObject lacks required field " + required);
      }
    }
    if (!(o.get("id") instanceof Long) || !(o.get("ver") instanceof Long)) {
      throw new IllegalArgumentException("ArchObject id and ver must be integers: " + o);
    }
    if (!(o.get("attrs") instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("ArchObject attrs must be an object: " + o);
    }
    if (o.get("datetime") != null) {
      try {
        Instant.parse(String.valueOf(o.get("datetime")));
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("ArchObject datetime is not date-time: " + o.get("datetime"));
      }
    }
    return o;
  }

  /** Целочисленный {@code id} объекта. */
  public static long id(Map<String, Object> archObject) {
    return (Long) archObject.get("id");
  }

  /** Номер версии {@code ver}. */
  public static long ver(Map<String, Object> archObject) {
    return (Long) archObject.get("ver");
  }

  /** Поля объекта. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> attrs(Map<String, Object> archObject) {
    return (Map<String, Object>) archObject.get("attrs");
  }

  /** Конец выборки: страница короче {@code pageSize} (в том числе пустая). */
  public static boolean isLastPage(List<?> page, int pageSize) {
    return page.size() < pageSize;
  }
}
