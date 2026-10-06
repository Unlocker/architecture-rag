package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeTemplatesTest {

  private static final QueryTemplate T = RuntimeTemplates.RUNTIME_FOOTPRINT;

  @Test
  void hasExpectedIdKindAndParameters() {
    assertThat(T.id()).isEqualTo("runtime_footprint");
    assertThat(T.kind()).isEqualTo(ResultKind.NODES);
    assertThat(T.parameters()).isEqualTo(Set.of("systemGid", "environment"));
    assertThat(T.maxLiteralDepth()).isZero();
    assertThat(T.hasDepthPlaceholder()).isFalse();
  }

  @Test
  void isReadOnlyAndHasNoVariableLengthPath() {
    assertThat(T.cypher())
        .doesNotContainPattern("(?i)\\b(CREATE|MERGE|SET|DELETE|REMOVE|CALL)\\b")
        .doesNotContain("*")
        .doesNotContain("Namespace");
  }

  @Test
  void environmentFilterIsSingleConditionWithoutOrExists() {
    assertThat(T.cypher())
        .doesNotContainPattern("(?i)OR\\s+EXISTS")
        .contains("$environment IS NULL OR e.code = $environment");
  }

  @Test
  void filtersOnlyCurrentFactsAndServicesAreMatchedBeforeDeployments() {
    assertThat(T.cypher())
        .contains("s.isCurrent = true", "svc.isCurrent = true", "d.isCurrent = true")
        .contains("e.isCurrent = true", "ro.validTo IS NULL", "c.isCurrent = true")
        .containsPattern("(?s)OPTIONAL MATCH \\(s\\)-\\[:DECOMPOSED_INTO\\]->\\(svc:Service\\)"
            + " WHERE svc.isCurrent = true\\s+OPTIONAL MATCH \\(svc\\)-\\[hd:HAS_DEPLOYMENT\\]");
  }

  @Test
  void hostedOnIsNotFilteredByValidity() {
    assertThat(T.cypher()).doesNotContain("ho.validTo");
  }

  @Test
  void usesOnlyActiveAssertionsForSources() {
    assertThat(T.cypher()).contains("SourceRecord {active: true})-[a:ASSERTS]->");
  }
}
