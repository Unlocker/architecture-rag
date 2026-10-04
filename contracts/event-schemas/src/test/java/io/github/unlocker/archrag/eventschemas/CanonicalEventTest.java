package io.github.unlocker.archrag.eventschemas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalEventTest {

  private static AssetUpsertedData data() {
    return new AssetUpsertedData("IT_SYSTEM", "EAM-1042", new SourceVersion("184"), Map.of("name", "x"));
  }

  @Test
  void buildsEnvelope() {
    var e = new CanonicalEvent("1", "urn:corp:eam", CanonicalEvent.TYPE_ASSET_UPSERTED, "it-system/EAM-1042",
        Instant.parse("2026-09-30T17:20:00Z"), "urn:corp:schema:asset-upserted:1", null, data());
    assertThat(e.specversion()).isEqualTo("1.0");
    assertThat(e.data().sourceVersion().value()).isEqualTo("184");
  }

  @Test
  void rejectsMissingId() {
    assertThatThrownBy(() -> new CanonicalEvent(" ", "s", "t", null, Instant.now(), "d", null, data()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dataRequiresSourceId() {
    assertThatThrownBy(() -> new AssetUpsertedData("IT_SYSTEM", "", new SourceVersion("1"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
