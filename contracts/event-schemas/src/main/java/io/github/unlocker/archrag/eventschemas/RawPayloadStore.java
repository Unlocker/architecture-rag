package io.github.unlocker.archrag.eventschemas;

/** Хранилище raw payload (S3-совместимое). Контент адресуется SHA-256: повторный put идемпотентен. */
public interface RawPayloadStore {

  /** Сохраняет байты под ключом {@code raw/<source>/<sha256>} и возвращает ссылку. */
  RawPayloadRef put(String source, byte[] content);

  /** Читает объект и проверяет {@code contentHash}; при расхождении бросает исключение. */
  byte[] get(RawPayloadRef ref);
}
