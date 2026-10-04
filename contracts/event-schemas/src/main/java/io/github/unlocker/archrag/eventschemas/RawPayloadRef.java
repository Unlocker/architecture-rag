package io.github.unlocker.archrag.eventschemas;

/**
 * Ссылка на raw payload в S3-совместимом хранилище: ключ объекта и SHA-256 содержимого (hex).
 * В PostgreSQL хранится только она, сам payload — в объектном хранилище.
 */
public record RawPayloadRef(String key, String contentHash) {

  public RawPayloadRef {
    if (key == null || key.isBlank() || contentHash == null || contentHash.isBlank()) {
      throw new IllegalArgumentException("key and contentHash are required");
    }
  }
}
