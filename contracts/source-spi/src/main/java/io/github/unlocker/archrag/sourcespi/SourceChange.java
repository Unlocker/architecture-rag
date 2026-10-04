package io.github.unlocker.archrag.sourcespi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Состояние одного объекта источника на момент чтения.
 *
 * <p>Инварианты: {@code sourceVersion} монотонно растёт для пары {@code (sourceType, sourceId)};
 * для {@link ChangeOperation#DELETE} payload пустой; payload неизменяем и считается недоверенным
 * текстом источника. Значения {@code null} в payload допустимы только как «поле не передано».
 *
 * @param sourceType тип объекта в источнике, например {@code IT_SYSTEM}
 * @param sourceId идентификатор объекта в источнике
 * @param sourceVersion монотонная версия объекта
 * @param operation upsert или delete
 * @param completeness полнота записи
 * @param updatedAt время изменения в источнике, UTC
 * @param payload поля объекта в формате источника
 */
public record SourceChange(
    String sourceType,
    String sourceId,
    long sourceVersion,
    ChangeOperation operation,
    Completeness completeness,
    Instant updatedAt,
    Map<String, Object> payload) {

  public SourceChange {
    Objects.requireNonNull(sourceType, "sourceType");
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(completeness, "completeness");
    Objects.requireNonNull(updatedAt, "updatedAt");
    // Map.copyOf не принимает null-значения, а «поле не передано» нужно сохранить как есть.
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
