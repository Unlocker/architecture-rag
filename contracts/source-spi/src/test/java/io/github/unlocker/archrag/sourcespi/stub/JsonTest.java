package io.github.unlocker.archrag.sourcespi.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void escapesAndNests() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", "q\"\\\n\u0001");
    m.put("b", List.of(1, true));
    m.put("c", null);
    assertThat(Json.write(m)).isEqualTo("{\"a\":\"q\\\"\\\\\\n\\u0001\",\"b\":[1,true],\"c\":null}");
  }
}
