package io.github.unlocker.archrag.normalizer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Типизированный доступ к payload источника. {@code null}, отсутствие и пустая строка означают «поле
 * не передано»; неверный тип — {@code INVALID_PAYLOAD}. Сообщения содержат только имена полей.
 */
final class Payload {

  private final Map<String, Object> values;

  Payload(Map<String, Object> values) {
    this.values = values;
  }

  /** Обязательное текстовое поле. */
  String required(String field) {
    return optional(field)
        .orElseThrow(
            () -> new NormalizationException("MISSING_REQUIRED_FIELD", "required field is missing: " + field));
  }

  /** Необязательное текстовое поле; пустое и {@code null} дают {@code empty}. */
  Optional<String> optional(String field) {
    Object v = values.get(field);
    if (v == null) {
      return Optional.empty();
    }
    if (!(v instanceof String s)) {
      throw invalidType(field);
    }
    String trimmed = s.strip();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  /** Необязательный вложенный объект; не-объект — {@code INVALID_PAYLOAD}. */
  Optional<Payload> optionalObject(String field) {
    Object v = values.get(field);
    if (v == null) {
      return Optional.empty();
    }
    if (!(v instanceof Map<?, ?> map)) {
      throw invalidType(field);
    }
    Map<String, Object> copy = new HashMap<>();
    map.forEach(
        (key, value) -> {
          if (!(key instanceof String name)) {
            throw invalidType(field);
          }
          copy.put(name, value);
        });
    return Optional.of(new Payload(copy));
  }

  /** Необязательный момент времени в ISO-8601 (UTC). */
  Optional<Instant> optionalInstant(String field) {
    return optional(field)
        .map(
            s -> {
              try {
                return Instant.parse(s);
              } catch (DateTimeParseException e) {
                throw invalidType(field);
              }
            });
  }

  /** Необязательное булево поле. */
  Optional<Boolean> optionalBoolean(String field) {
    Object v = values.get(field);
    if (v == null) {
      return Optional.empty();
    }
    if (!(v instanceof Boolean b)) {
      throw invalidType(field);
    }
    return Optional.of(b);
  }

  /** Необязательный список строк; пустые элементы отбрасываются. */
  List<String> optionalList(String field) {
    Object v = values.get(field);
    if (v == null) {
      return List.of();
    }
    if (!(v instanceof List<?> list)) {
      throw invalidType(field);
    }
    List<String> result = new ArrayList<>();
    for (Object element : list) {
      if (!(element instanceof String s)) {
        throw invalidType(field);
      }
      if (!s.isBlank()) {
        result.add(s.strip());
      }
    }
    return result;
  }

  /**
   * Одиночное поле-ссылка на объект EAM: целый {@code id} либо {@code null}/отсутствие (решение 3
   * {@code docs/06-eam-integration.md}). Список и нецелое значение — {@code INVALID_PAYLOAD}: списочная форма не читается.
   */
  Optional<String> optionalIdRef(String field) {
    Object v = values.get(field);
    if (v == null) {
      return Optional.empty();
    }
    if (!(v instanceof Long || v instanceof Integer)) {
      throw invalidType(field);
    }
    return Optional.of(v.toString());
  }

  private static NormalizationException invalidType(String field) {
    return new NormalizationException("INVALID_PAYLOAD", "field has unexpected type: " + field);
  }
}
