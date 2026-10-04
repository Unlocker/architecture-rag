package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Превращает {@link CanonicalEvent} в {@code GraphCommand}'ы: выбирает {@link CanonicalMapper}
 * источника, проверяет тип события и версию схемы и собирает команды.
 *
 * <p>Инварианты: чистая функция без побочных эффектов (безопасна при повторе); ошибка никогда не
 * глушится — любая нештатная ситуация даёт {@link NormalizationResult.Quarantined} с кодом
 * ({@code UNSUPPORTED_EVENT_TYPE}, {@code UNKNOWN_SOURCE}, {@code UNKNOWN_SCHEMA_VERSION},
 * {@code UNKNOWN_SOURCE_TYPE}, {@code MISSING_REQUIRED_FIELD}, {@code INVALID_PAYLOAD}); частичная
 * запись не затирает известное — отсутствующие поля остаются {@code null}/без команды.
 * Причины не содержат значений из источника.
 */
public final class Normalizer {

  private static final String SOURCE_PREFIX = "urn:corp:";
  private static final String SCHEMA_PREFIX = "urn:corp:schema:asset-upserted:";

  private final Map<SourceSystemCode, CanonicalMapper> mappers = new EnumMap<>(SourceSystemCode.class);
  private final ReferenceResolver resolver;

  public Normalizer(List<CanonicalMapper> mappers, ReferenceResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    mappers.forEach(m -> this.mappers.put(m.source(), m));
  }

  /** Нормализатор со стандартными мапперами четырёх источников PoC. */
  public static Normalizer standard(ReferenceResolver resolver) {
    return new Normalizer(
        List.of(new EamMapper(), new ScmMapper(), new CmdbMapper(), new DeployMapMapper()), resolver);
  }

  /**
   * Нормализует событие.
   *
   * @param raw ссылка на raw payload; её hash попадает в {@code SourceRecord}
   */
  public NormalizationResult normalize(CanonicalEvent event, RawPayloadRef raw) {
    if (!CanonicalEvent.TYPE_ASSET_UPSERTED.equals(event.type())) {
      return quarantine("UNSUPPORTED_EVENT_TYPE", "event type is not supported");
    }
    SourceSystemCode source = sourceCode(event.source());
    CanonicalMapper mapper = source == null ? null : mappers.get(source);
    if (mapper == null) {
      return quarantine("UNKNOWN_SOURCE", "no mapper for event source");
    }
    String schemaVersion = schemaVersion(event.dataschema());
    if (schemaVersion == null || !mapper.supportedSchemaVersions().contains(schemaVersion)) {
      return quarantine("UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported");
    }
    AssetEventData data = event.data();
    var record =
        new SourceRecord(
            new SourceKey(source, data.sourceType(), data.sourceId()),
            data.sourceVersion().value(),
            raw.contentHash(),
            event.time(),
            true);
    var sink = new CommandSink(record.key(), resolver);
    try {
      mapper.map(new CanonicalMapper.Input(record, data.payload(), event.time()), sink);
    } catch (NormalizationException e) {
      return quarantine(e.code(), e.getMessage());
    } catch (IllegalArgumentException e) {
      // Нарушен инвариант record'а модели; текст исключения может содержать значения источника,
      // поэтому в причину он не попадает.
      return quarantine("INVALID_PAYLOAD", "payload violates canonical model invariants");
    }
    return sink.result();
  }

  private static NormalizationResult.Quarantined quarantine(String code, String reason) {
    return new NormalizationResult.Quarantined(code, reason);
  }

  private static SourceSystemCode sourceCode(String urn) {
    if (!urn.startsWith(SOURCE_PREFIX)) {
      return null;
    }
    return switch (urn.substring(SOURCE_PREFIX.length())) {
      case "eam" -> SourceSystemCode.EAM;
      case "scm" -> SourceSystemCode.SCM;
      case "cmdb" -> SourceSystemCode.CMDB;
      case "deploymap" -> SourceSystemCode.DEPLOYMAP;
      default -> null;
    };
  }

  private static String schemaVersion(String dataschema) {
    return dataschema.startsWith(SCHEMA_PREFIX) ? dataschema.substring(SCHEMA_PREFIX.length()) : null;
  }
}
