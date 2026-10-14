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

  /** Тип события удаления объекта в источнике (tombstone). */
  public static final String TYPE_ASSET_DELETED = "architecture.asset.deleted.v1";

  /** Тип маркера завершения полного snapshot. */
  public static final String TYPE_SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";

  /** {@code dataschema} события upsert. */
  public static final String SCHEMA_UPSERTED = "urn:corp:schema:asset-upserted:1";

  /** {@code dataschema} события удаления. */
  public static final String SCHEMA_DELETED = "urn:corp:schema:asset-deleted:1";

  /** {@code dataschema} маркера snapshot-complete. */
  public static final String SCHEMA_SNAPSHOT_COMPLETE = "urn:corp:schema:snapshot-complete:1";

  /** {@code sourceType} маркера snapshot; {@code sourceId} — {@code syncRunId}. */
  public static final String SYNC_RUN_SOURCE_TYPE = "SYNC_RUN";

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
