package io.github.unlocker.archrag.graphquerycore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryTemplateRegistryTest {

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  static QueryTemplate template(String id, String cypher, String... params) {
    return new QueryTemplate(id, cypher, Set.of(params), null);
  }

  @Test
  void duplicateIdIsRejected() {
    QueryTemplate a = template("t", "MATCH (n) RETURN n LIMIT $limit");
    assertThatThrownBy(() -> QueryTemplateRegistry.of(List.of(a, a), LIMITS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void registeredTemplateIsFound() {
    QueryTemplateRegistry registry =
        QueryTemplateRegistry.of(List.of(template("t", "MATCH (n) RETURN n LIMIT $limit")), LIMITS);
    assertThat(registry.find("t")).isPresent();
    assertThat(registry.find("other")).isEmpty();
  }

  @Test
  void foreignPlaceholderIsRejected() {
    assertThatThrownBy(() -> template("t", "MATCH (n:{label}) RETURN n LIMIT $limit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("placeholder");
  }

  @Test
  void mapLiteralIsNotAPlaceholder() {
    assertThat(template("t", "MATCH (n {gid: $gid}) RETURN n LIMIT $limit", "gid").id()).isEqualTo("t");
  }

  @Test
  void templateWithoutLimitIsRejected() {
    assertThatThrownBy(() -> template("t", "MATCH (n) RETURN n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LIMIT");
  }

  @Test
  void declaredAndUsedParametersMustMatch() {
    assertThatThrownBy(() -> template("t", "MATCH (n {gid: $gid}) RETURN n LIMIT $limit"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> template("t", "MATCH (n) RETURN n LIMIT $limit", "gid"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void limitParameterCannotBeDeclared() {
    assertThatThrownBy(() -> template("t", "MATCH (n) RETURN n LIMIT $limit", "limit"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unboundedTraversalIsRejected() {
    for (String pattern : List.of("[*]", "[*1..]", "[r:DEPENDS_ON*]", "[:A|B*2..]")) {
      assertThatThrownBy(
              () -> template("t", "MATCH (a)-" + pattern + "->(b) RETURN b LIMIT $limit"))
          .as(pattern)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void boundedTraversalIsAccepted() {
    assertThat(template("t", "MATCH (a)-[:DEPENDS_ON*1..{maxDepth}]->(b) RETURN b LIMIT $limit")
            .hasDepthPlaceholder())
        .isTrue();
    assertThat(template("t", "MATCH (a)-[:DEPENDS_ON*1..3]->(b) RETURN b LIMIT $limit")
            .hasDepthPlaceholder())
        .isFalse();
  }

  @Test
  void depthPlaceholderOutsideTraversalIsRejected() {
    assertThatThrownBy(() -> template("t", "MATCH (n) WHERE n.d = {maxDepth} RETURN n LIMIT $limit"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renderRejectsDepthOutOfRange() {
    QueryTemplate t = template("t", "MATCH (a)-[:R*1..{maxDepth}]->(b) RETURN b LIMIT $limit");
    assertThat(t.render(2, 6)).contains("*1..2]");
    assertThatThrownBy(() -> t.render(0, 6)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> t.render(7, 6)).isInstanceOf(IllegalArgumentException.class);
  }
}
