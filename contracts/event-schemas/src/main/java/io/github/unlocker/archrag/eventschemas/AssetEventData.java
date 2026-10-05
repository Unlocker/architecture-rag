package io.github.unlocker.archrag.eventschemas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Поле {@code data} события {@code architecture.asset.upserted.v1}.
 *
 * <p>{@code payload} — нормализованное или source-специфичное содержимое; для лога и проверок оно
 * недоверенное. Инварианты: {@code sourceType}, {@code sourceId} и {@code sourceVersion} заданы; значения {@code null}
 * в payload допустимы и означают «поле не передано».
 */
public record AssetEventData(String sourceType, String sourceId, SourceVersion sourceVersion, Map<String, Object> payload) {

  public AssetEventData {
    requireText(sourceType, "sourceType");
    requireText(sourceId, "sourceId");
    if (sourceVersion == null) {
      throw new IllegalArgumentException("sourceVersion is required");
    }
    // Map.copyOf не принимает null-значения, а «поле не передано» (null) нужно сохранить как есть.
    payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  private static void requireText(String v, String name) {
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
