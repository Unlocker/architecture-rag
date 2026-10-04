package io.github.unlocker.archrag.eventschemas;

import java.util.Map;

/**
 * Поле {@code data} события {@code architecture.asset.upserted.v1}.
 *
 * <p>{@code payload} — нормализованное или source-специфичное содержимое; для лога и проверок оно
 * недоверенное. Инварианты: {@code sourceType}, {@code sourceId} и {@code sourceVersion} заданы.
 */
public record AssetUpsertedData(String sourceType, String sourceId, SourceVersion sourceVersion, Map<String, Object> payload) {

  public AssetUpsertedData {
    requireText(sourceType, "sourceType");
    requireText(sourceId, "sourceId");
    if (sourceVersion == null) {
      throw new IllegalArgumentException("sourceVersion is required");
    }
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  private static void requireText(String v, String name) {
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
