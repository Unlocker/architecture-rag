package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import java.time.Instant;
import java.util.Map;

final class Fixtures {

  static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
  static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "abc123");

  private Fixtures() {}

  static CanonicalEvent event(String source, SourceChange c) {
    return event(source, "urn:corp:schema:asset-upserted:1", c.sourceType(), c.sourceId(), c.payload());
  }

  static CanonicalEvent event(
      String source, String dataschema, String type, String id, Map<String, Object> payload) {
    return new CanonicalEvent(
        "e-" + id, source, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, T, dataschema, null,
        new AssetEventData(type, id, new SourceVersion("5"), payload));
  }
}
