package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void roundTripKeepsStructureAndNulls() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "a\"b\\c\n\u0001");
    m.put("n", 42L);
    m.put("d", 1.5);
    m.put("flag", true);
    m.put("none", null);
    m.put("list", List.of("x", Map.of("k", "v")));

    assertThat(Json.parseObject(Json.write(m))).isEqualTo(m);
  }

  @Test
  void parsesUnicodeEscapes() {
    assertThat(Json.parseObject("{\"a\":\"\\u0041\"}")).containsEntry("a", "A");
  }

  @Test
  void writeIsDeterministic() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("b", 1L);
    m.put("a", 2L);
    assertThat(Json.write(m)).isEqualTo("{\"b\":1,\"a\":2}");
  }

  @Test
  void rejectsMalformedWithoutEchoingContent() {
    assertThatThrownBy(() -> Json.parse("{\"secret-token\": nope}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret-token");
    assertThatThrownBy(() -> Json.parse("{} x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("[1,")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parseObject("[1]")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void limitsNestingDepth() {
    String deep = "[".repeat(200) + "]".repeat(200);
    assertThatThrownBy(() -> Json.parse(deep)).isInstanceOf(IllegalArgumentException.class);
  }
}
