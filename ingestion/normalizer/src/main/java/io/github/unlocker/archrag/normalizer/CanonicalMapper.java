package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Маппер одного источника: payload в формате источника → {@code GraphCommand}'ы. Единственное
 * место, где source DTO ещё существует; наружу выходят только команды канонической модели.
 */
public interface CanonicalMapper {

  /** Источник, который обслуживает маппер. */
  SourceSystemCode source();

  /** Поддерживаемые версии схемы {@code asset-upserted} (последний сегмент {@code dataschema}). */
  Set<String> supportedSchemaVersions();

  /**
   * Строит команды для одного объекта.
   *
   * @throws NormalizationException если payload нельзя отобразить (код и причина без значений)
   */
  void map(Input input, CommandSink sink);

  /**
   * Вход маппера.
   *
   * @param record provenance объекта (ключ, версия, hash raw payload, время)
   * @param payload payload источника; недоверенные данные
   * @param time время события, UTC
   */
  record Input(SourceRecord record, Map<String, Object> payload, Instant time) {

    /** Ключ объекта события. */
    public SourceKey key() {
      return record.key();
    }
  }
}
