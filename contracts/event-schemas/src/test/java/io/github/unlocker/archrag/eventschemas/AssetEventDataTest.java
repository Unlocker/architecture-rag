package io.github.unlocker.archrag.eventschemas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

class AssetEventDataTest {

  @Test
  void payloadKeepsNullValuesAsNotProvided() {
    var payload = new HashMap<String, Object>();
    payload.put("name", "x");
    payload.put("language", null);
    var data = new AssetEventData("SERVICE", "s1", new SourceVersion("1"), payload);
    payload.put("late", "mutation");
    assertThat(data.payload()).containsEntry("language", null).doesNotContainKey("late");
  }
}
