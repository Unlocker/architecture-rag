package io.github.unlocker.archrag.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TraceDependenciesToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  private final GraphQueries queries = mock(GraphQueries.class);
  private final QueryLimits limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
  private TraceDependenciesTool tool;

  @BeforeEach
  void setUp() {
    tool = new TraceDependenciesTool(queries, limits);
  }

  private static Map<String, Object> node(String gid) {
    return Map.of("gid", gid, "label", "Service", "name", gid, "lastSeenAt", "2026-01-01T00:00:00Z");
  }

  private static Map<String, Object> rel(String type, String from, String to) {
    return Map.of("type", type, "from", from, "to", to, "validFrom", "2026-01-01T00:00:00Z",
        "assertedBySource", "EAM", "assertedByType", "relation", "assertedById", type + "/" + from + "/" + to);
  }

  private void returning(String template, boolean truncated, Map<String, Object>... rows) {
    when(queries.execute(eq(template), any(), any()))
        .thenReturn(new QueryResult(template, List.of(rows), truncated, rows.length, Duration.ZERO));
  }

  @Test
  @SuppressWarnings("unchecked")
  void defaultsAreDownstreamFullAllowlistAndConfiguredDepth() {
    returning("trace_downstream", false);
    returning("get_asset", false, Map.of("gid", GID));

    tool.traceDependencies(GID, null, null, null, null);

    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<ResultBudget> budget = ArgumentCaptor.forClass(ResultBudget.class);
    verify(queries).execute(eq("trace_downstream"), params.capture(), budget.capture());
    assertThat(params.getValue()).containsOnlyKeys("gid", "relationTypes").containsEntry("gid", GID);
    assertThat((List<String>) params.getValue().get("relationTypes"))
        .containsExactly("DEPENDS_ON", "DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON", "HOSTED_ON", "OWNED_BY");
    assertThat(budget.getValue().maxDepth()).isEqualTo(6);
    assertThat(budget.getValue().maxPaths()).isEqualTo(50);
  }

  @Test
  void upstreamUsesUpstreamTemplateAndRequestedDepthAndTypes() {
    returning("trace_upstream", false);
    returning("get_asset", false, Map.of("gid", GID));

    tool.traceDependencies(GID, "UPSTREAM", 2, List.of("RUNS_ON"), "trace");

    ArgumentCaptor<ResultBudget> budget = ArgumentCaptor.forClass(ResultBudget.class);
    verify(queries).execute(eq("trace_upstream"), any(), budget.capture());
    assertThat(budget.getValue().maxDepth()).isEqualTo(2);
  }

  @Test
  void deduplicatesNodesAndRelationsAcrossPaths() {
    returning(
        "trace_downstream",
        true,
        Map.of("nodes", List.of(node("a"), node("b")), "relations", List.of(rel("DEPENDS_ON", "a", "b"))),
        Map.of("nodes", List.of(node("a"), node("b"), node("c")),
            "relations", List.of(rel("DEPENDS_ON", "a", "b"), rel("DEPENDS_ON", "b", "c"))));

    TraceDependenciesResult result = tool.traceDependencies(GID, "downstream", null, null, null);

    assertThat(result.truncated()).isTrue();
    assertThat(result.nodes()).extracting(TraceDependenciesResult.NodeRef::gid).containsExactly("a", "b", "c");
    assertThat(result.relations()).hasSize(2);
    assertThat(result.paths()).hasSize(2);
    assertThat(result.paths().get(1).nodes()).containsExactly("a", "b", "c");
    assertThat(result.paths().get(1).relations())
        .containsExactly(
            new TraceDependenciesResult.RelationKey("DEPENDS_ON", "a", "b"),
            new TraceDependenciesResult.RelationKey("DEPENDS_ON", "b", "c"));
  }

  @Test
  void emptyTraceOfExistingAssetIsNotAnError() {
    returning("trace_downstream", false);
    returning("get_asset", false, Map.of("gid", GID));

    TraceDependenciesResult result = tool.traceDependencies(GID, null, null, null, null);

    assertThat(result.paths()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void unknownAssetIsAnErrorWithoutGid() {
    returning("trace_downstream", false);
    returning("get_asset", false);

    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found");
  }

  @Test
  void rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput() {
    assertThatThrownBy(() -> tool.traceDependencies("leaky-value", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leaky");
    assertThatThrownBy(() -> tool.traceDependencies(null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, "sideways-leak", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leak");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, 7, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, List.of("ASSERTS"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("ASSERTS");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, List.of("RUNS_ON]-() DETACH DELETE n"), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("DETACH");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "bogus-leak"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leak");
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  void impactModeIsNotSupportedYet() {
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "impact"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("mode impact is not supported yet");
    verify(queries, never()).execute(any(), any(), any());
  }
}
