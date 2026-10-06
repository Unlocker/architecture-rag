package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyTemplatesTest {

  private static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  @Test
  void templatesAreValidAndRegistrable() {
    var registry = QueryTemplateRegistry.of(
        List.of(DependencyTemplates.TRACE_DOWNSTREAM, DependencyTemplates.TRACE_UPSTREAM), LIMITS);

    assertThat(registry.find("trace_downstream")).isPresent();
    assertThat(registry.find("trace_upstream")).isPresent();
  }

  @Test
  void templatesAreBoundedPathTemplates() {
    for (QueryTemplate t : List.of(DependencyTemplates.TRACE_DOWNSTREAM, DependencyTemplates.TRACE_UPSTREAM)) {
      assertThat(t.kind()).isEqualTo(ResultKind.PATHS);
      assertThat(t.parameters()).containsExactlyInAnyOrder("gid", "relationTypes");
      assertThat(t.hasDepthPlaceholder()).isTrue();
      assertThat(t.render(3, 6)).contains("[*1..3]").doesNotContain("{maxDepth}");
    }
  }

  @Test
  void templatesFilterClosedRelationsAndNotCurrentNodes() {
    for (QueryTemplate t : List.of(DependencyTemplates.TRACE_DOWNSTREAM, DependencyTemplates.TRACE_UPSTREAM)) {
      assertThat(t.cypher()).contains("r.validTo IS NULL").contains("n.isCurrent = true").contains("type(r) IN $relationTypes");
    }
  }

  @Test
  void directionsDiffer() {
    assertThat(DependencyTemplates.TRACE_DOWNSTREAM.cypher()).contains("-[*1..{maxDepth}]->");
    assertThat(DependencyTemplates.TRACE_UPSTREAM.cypher()).contains("<-[*1..{maxDepth}]-");
  }

  @Test
  void allowlistIsExactlyTheTraceRelations() {
    assertThat(DependencyTemplates.TRACE_RELATION_TYPES)
        .containsExactly("DEPENDS_ON", "DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON", "HOSTED_ON", "OWNED_BY");
  }

  @Test
  void noTemplateOrdersBeforeLimit() {
    for (QueryTemplate t :
        List.of(DependencyTemplates.TRACE_DOWNSTREAM, DependencyTemplates.TRACE_UPSTREAM, DependencyTemplates.IMPACT_UPSTREAM)) {
      assertThat(t.cypher()).doesNotContain("ORDER BY").contains("WITH p LIMIT $limit");
    }
  }

  @Test
  void impactTemplateGoesUpstreamToServicesAndSystemsFilteredByEnvironment() {
    var t = DependencyTemplates.IMPACT_UPSTREAM;

    assertThat(t.id()).isEqualTo("trace_impact");
    assertThat(t.kind()).isEqualTo(ResultKind.PATHS);
    assertThat(t.parameters()).containsExactlyInAnyOrder("gid", "relationTypes", "environment");
    assertThat(t.cypher())
        .contains("<-[*1..{maxDepth}]-(t:Service|ITSystem)")
        .contains("IN_ENVIRONMENT")
        .contains("e.validTo IS NULL")
        .contains("r.validTo IS NULL");
    assertThat(t.render(4, 6)).contains("[*1..4]");
    assertThat(QueryTemplateRegistry.of(List.of(t), LIMITS).find("trace_impact")).isPresent();
  }

  @Test
  void impactAllowlistIsExactAndSubsetOfTraceAllowlist() {
    assertThat(DependencyTemplates.IMPACT_RELATION_TYPES)
        .containsExactly("RUNS_ON", "HOSTED_ON", "HAS_DEPLOYMENT", "DECOMPOSED_INTO", "DEPENDS_ON")
        .doesNotContain("OWNED_BY")
        .isSubsetOf(DependencyTemplates.TRACE_RELATION_TYPES);
    assertThat(DependencyTemplates.IMPACT_TARGET_LABELS).containsExactly("Service", "ITSystem");
  }

  @Test
  void projectionCarriesProvenanceFreshnessAndConflicts() {
    for (QueryTemplate t : List.of(DependencyTemplates.TRACE_UPSTREAM, DependencyTemplates.IMPACT_UPSTREAM)) {
      assertThat(t.cypher()).contains("sourceFetchedAt").contains("sourceActive").contains("a.conflicts IS NOT NULL");
    }
  }
}
