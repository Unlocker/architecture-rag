package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyJsonTest {

  @Test
  void roundTripsAllowedValueTypes() {
    Map<String, Object> props =
        Map.of(
            "kind", "SYNC \"quoted\" \\ \n tab\t ünï",
            "critical", true,
            "count", 7L,
            "ratio", 0.5,
            "since", Instant.parse("2026-10-01T10:00:00Z"),
            "tags", List.of("a", "b"));

    assertThat(PropertyJson.read(PropertyJson.write(props))).containsExactlyInAnyOrderEntriesOf(props);
  }

  @Test
  void emptyMapRoundTrips() {
    assertThat(PropertyJson.read(PropertyJson.write(Map.of()))).isEmpty();
  }

  @Test
  void rejectsMalformedAndUnsupported() {
    assertThatThrownBy(() -> PropertyJson.read("{\"a\":")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PropertyJson.read("{\"a\":\"x\\")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PropertyJson.read("{\"a\":\"\\u12")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PropertyJson.read("[]")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PropertyJson.write(Map.of("a", new Object()))).isInstanceOf(IllegalArgumentException.class);
  }
}
