package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FootprintTemplatesTest {

  private static final QueryTemplate T = FootprintTemplates.FIND_RUNTIME_FOOTPRINT;

  @Test
  void hasExpectedIdKindAndParameters() {
    assertThat(T.id()).isEqualTo("find_runtime_footprint");
    assertThat(T.kind()).isEqualTo(ResultKind.NODES);
    assertThat(T.parameters()).isEqualTo(Set.of("systemGid", "environment"));
  }

  @Test
  void isReadOnlyAndHasNoVariableLengthPath() {
    assertThat(T.cypher())
        .doesNotContainPattern("(?i)\\b(CREATE|MERGE|SET|DELETE|REMOVE|CALL)\\b")
        .doesNotContain("*")
        .doesNotContain("{maxDepth}");
    assertThat(T.hasDepthPlaceholder()).isFalse();
  }

  @Test
  void environmentFilterIsSingleConditionWithoutOrExists() {
    assertThat(T.cypher())
        .doesNotContainPattern("(?i)OR\\s+EXISTS")
        .contains("$environment IS NULL OR env.code = $environment");
  }

  @Test
  void filtersOnlyCurrentFacts() {
    assertThat(T.cypher())
        .contains("svc.isCurrent = true", "d.isCurrent = true", "env.isCurrent = true")
        .contains("ro.validTo IS NULL", "ci.isCurrent = true", "ho.validTo IS NULL");
  }
}
