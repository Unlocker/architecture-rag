package io.github.unlocker.archrag.mcpserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToolArgumentsTest {

  @Test
  void keysAreSorted() {
    var args = new LinkedHashMap<String, Object>();
    args.put("b", 1);
    args.put("a", 2);
    assertThat(ToolArguments.normalize(args).keySet()).containsExactly("a", "b");
  }

  @Test
  void nullArgumentsGiveEmptyMap() {
    assertThat(ToolArguments.normalize(null)).isEmpty();
  }

  @Test
  void longStringIsTruncatedWithMarker() {
    var result = ToolArguments.normalize(Map.of("q", "x".repeat(300)));
    String value = (String) result.get("q");
    assertThat(value).startsWith("x".repeat(256)).contains("<+44 chars>").hasSizeLessThan(300);
  }

  @Test
  void shortStringAndScalarsAreKept() {
    var result = ToolArguments.normalize(Map.of("s", "abc", "n", 5, "f", true));
    assertThat(result).containsEntry("s", "abc").containsEntry("n", 5).containsEntry("f", true);
  }

  @Test
  void collectionIsCutToTwentyItems() {
    List<Integer> items = IntStream.range(0, 50).boxed().toList();
    List<?> result = (List<?>) ToolArguments.normalize(Map.of("ids", items)).get("ids");
    assertThat(result).hasSize(21).last().isEqualTo("<+30 more>");
  }

  @Test
  void sensitiveKeysAreMaskedCaseInsensitivelyAtAnyDepth() {
    var args =
        Map.of(
            "AccessToken", "abc",
            "nested", Map.of("db_Password", "p", "ok", "v"),
            "list", List.of(Map.of("clientSecret", "s")));
    var result = ToolArguments.normalize(args);
    assertThat(result).containsEntry("AccessToken", "***");
    assertThat(result.get("nested")).isEqualTo(Map.of("db_Password", "***", "ok", "v"));
    assertThat(result.get("list")).isEqualTo(List.of(Map.of("clientSecret", "***")));
  }

  @Test
  void deepNestingIsCut() {
    Object value = "leaf";
    for (int i = 0; i < 10; i++) {
      value = Map.of("k", value);
    }
    assertThat(ToolArguments.normalize(Map.of("root", value)).toString()).contains("<depth-limit>");
  }
}
