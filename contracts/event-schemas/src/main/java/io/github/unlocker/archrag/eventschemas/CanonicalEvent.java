package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;

/**
 * Канонический CloudEvent {@code architecture.asset.upserted.v1} (vision, «Каноническое событие»).
 *
 * <p>Инварианты: {@code specversion} = {@code 1.0}; пара {@code (source, id)} уникальна и служит
 * ключом дедупликации; {@code time} — UTC-момент.
 *
 * @param id идентификатор события, уникальный в пределах {@code source}
 * @param source источник, например {@code urn:corp:eam}
 * @param subject объект события, например {@code it-system/EAM-1042}
 * @param dataschema схема данных, например {@code urn:corp:schema:asset-upserted:1}
 * @param correlationid идентификатор корреляции (может быть {@code null})
 */
public record CanonicalEvent(
    String id,
    String source,
    String type,
    String subject,
    Instant time,
    String dataschema,
    String correlationid,
    AssetEventData data) {

  /** Версия спецификации CloudEvents. */
  public static final String SPEC_VERSION = "1.0";

  /** Тип события upsert актива. */
  public static final String TYPE_ASSET_UPSERTED = "architecture.asset.upserted.v1";

  public CanonicalEvent {
    requireText(id, "id");
    requireText(source, "source");
    requireText(type, "type");
    requireText(dataschema, "dataschema");
    if (time == null || data == null) {
      throw new IllegalArgumentException("time and data are required");
    }
  }

  /** Всегда {@link #SPEC_VERSION}. */
  public String specversion() {
    return SPEC_VERSION;
  }

  private static void requireText(String v, String name) {
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
