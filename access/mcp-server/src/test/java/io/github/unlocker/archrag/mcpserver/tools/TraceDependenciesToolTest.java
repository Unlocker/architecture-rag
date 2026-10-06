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
import io.github.unlocker.archrag.mcpserver.graph.StalenessProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TraceDependenciesToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneOffset.UTC);

  private final GraphQueries queries = mock(GraphQueries.class);
  private final QueryLimits limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
  private TraceDependenciesTool tool;

  @BeforeEach
  void setUp() {
    tool = new TraceDependenciesTool(queries, limits, new StalenessProperties(Duration.ofDays(7)), CLOCK);
  }

  private static Map<String, Object> node(String gid) {
    return node(gid, "2026-01-14T00:00:00Z", List.of());
  }

  private static Map<String, Object> node(String gid, String lastSeenAt, List<Map<String, Object>> conflicts) {
    Map<String, Object> n = new HashMap<>(Map.of("gid", gid, "label", "Service", "name", gid, "conflicts", conflicts));
    n.put("lastSeenAt", lastSeenAt);
    return n;
  }

  private static Map<String, Object> rel(String type, String from, String to) {
    return rel(type, from, to, "2026-01-14T00:00:00Z", true);
  }

  private static Map<String, Object> rel(String type, String from, String to, String fetchedAt, Boolean active) {
    Map<String, Object> r = new HashMap<>(Map.of("type", type, "from", from, "to", to, "validFrom", "2026-01-01T00:00:00Z",
        "assertedBySource", "EAM", "assertedByType", "relation", "assertedById", type + "/" + from + "/" + to));
    r.put("sourceFetchedAt", fetchedAt);
    r.put("sourceActive", active);
    return r;
  }

  private static Map<String, Object> row(List<Map<String, Object>> nodes, Map<String, Object>... relations) {
    return Map.of("nodes", nodes, "relations", List.of(relations));
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

    tool.traceDependencies(GID, null, null, null, null, null);

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

    tool.traceDependencies(GID, "UPSTREAM", 2, List.of("RUNS_ON"), "trace", null);

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

    TraceDependenciesResult result = tool.traceDependencies(GID, "downstream", null, null, null, null);

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

    TraceDependenciesResult result = tool.traceDependencies(GID, null, null, null, null, null);

    assertThat(result.paths()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void unknownAssetIsAnErrorWithoutGid() {
    returning("trace_downstream", false);
    returning("get_asset", false);

    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found");
  }

  @Test
  void rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput() {
    assertThatThrownBy(() -> tool.traceDependencies("leaky-value", null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leaky");
    assertThatThrownBy(() -> tool.traceDependencies(null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, "sideways-leak", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leak");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, 0, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, 7, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, List.of("ASSERTS"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("ASSERTS");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, List.of("RUNS_ON]-() DETACH DELETE n"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("DETACH");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "bogus-leak", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("leak");
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  void rejectsBadArgumentsForImpact() {
    assertThatThrownBy(() -> tool.traceDependencies(GID, "downstream-leak", null, null, "impact", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("direction must be upstream for mode impact");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, List.of("OWNED_BY"), "impact", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("OWNED_BY");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "trace", "prod"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("environment is supported only for mode impact");
    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "impact", "x".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void impactUsesImpactTemplateWithEnvironmentAndImpactAllowlist() {
    returning("trace_impact", false);
    returning("get_asset", false, Map.of("gid", GID));

    var result = tool.traceDependencies(GID, "UPSTREAM", null, null, "IMPACT", " prod ");

    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(queries).execute(eq("trace_impact"), params.capture(), any());
    assertThat(params.getValue()).containsOnlyKeys("gid", "relationTypes", "environment").containsEntry("environment", "prod");
    assertThat((List<String>) params.getValue().get("relationTypes"))
        .containsExactly("RUNS_ON", "HOSTED_ON", "HAS_DEPLOYMENT", "DECOMPOSED_INTO", "DEPENDS_ON");
    assertThat(result.mode()).isEqualTo("impact");
    assertThat(result.direction()).isEqualTo("upstream");
    assertThat(result.environment()).isEqualTo("prod");
    assertThat(result.staleAfter()).isEqualTo("PT168H");
  }

  @Test
  @SuppressWarnings("unchecked")
  void impactWithoutEnvironmentPassesNull() {
    returning("trace_impact", false);
    returning("get_asset", false, Map.of("gid", GID));

    tool.traceDependencies(GID, null, null, null, "impact", " ");

    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(queries).execute(eq("trace_impact"), params.capture(), any());
    assertThat(params.getValue()).containsEntry("environment", null);
  }

  @Test
  void impactUnknownAssetIsAnError() {
    returning("trace_impact", false);
    returning("get_asset", false);

    assertThatThrownBy(() -> tool.traceDependencies(GID, null, null, null, "impact", null))
        .hasMessage("asset not found");
  }

  @Test
  @SuppressWarnings("unchecked")
  void impactSortsPathsAndListsAffectedWithoutDuplicates() {
    var vm = node("vm");
    var svcB = node("b");
    var svcA = node("a");
    var sys = node("sys");
    returning(
        "trace_impact",
        false,
        row(List.of(vm, svcB, sys), rel("HAS_DEPLOYMENT", "b", "vm"), rel("DECOMPOSED_INTO", "sys", "b")),
        row(List.of(vm, svcB), rel("HAS_DEPLOYMENT", "b", "vm")),
        row(List.of(vm, svcA), rel("HAS_DEPLOYMENT", "a", "vm")),
        row(List.of(vm, svcA, sys), rel("HAS_DEPLOYMENT", "a", "vm"), rel("DECOMPOSED_INTO", "sys", "a")));

    var result = tool.traceDependencies(GID, null, null, null, "impact", null);

    assertThat(result.paths()).extracting(TraceDependenciesResult.TracePath::nodes)
        .containsExactly(List.of("vm", "a"), List.of("vm", "b"), List.of("vm", "a", "sys"), List.of("vm", "b", "sys"));
    assertThat(result.affected()).containsExactly("a", "b", "sys");
  }

  @Test
  void traceModeHasNoAffected() {
    returning("trace_downstream", false, row(List.of(node("a"), node("b")), rel("DEPENDS_ON", "a", "b")));

    assertThat(tool.traceDependencies(GID, null, null, null, null, null).affected()).isEmpty();
  }

  @Test
  void nodeStalenessBoundaries() {
    // Часы: 2026-01-15T00:00Z, порог 7 дней -> граница 2026-01-08T00:00Z.
    returning(
        "trace_downstream",
        false,
        row(List.of(node("start"), node("onThreshold", "2026-01-08T00:00:00Z", List.of())), rel("DEPENDS_ON", "start", "onThreshold")),
        row(List.of(node("start"), node("justOlder", "2026-01-07T23:59:59Z", List.of())), rel("DEPENDS_ON", "start", "justOlder")),
        row(List.of(node("start"), node("never", null, List.of())), rel("DEPENDS_ON", "start", "never")));

    var result = tool.traceDependencies(GID, null, null, null, null, null);

    var stale = result.nodes().stream().collect(java.util.stream.Collectors.toMap(
        TraceDependenciesResult.NodeRef::gid, TraceDependenciesResult.NodeRef::stale));
    assertThat(stale).containsEntry("onThreshold", false).containsEntry("justOlder", true).containsEntry("never", true);
  }

  @Test
  void relationStalenessCoversMissingInactiveOldAndFresh() {
    returning(
        "trace_downstream",
        false,
        row(List.of(node("s"), node("fresh")), rel("DEPENDS_ON", "s", "fresh", "2026-01-14T00:00:00Z", true)),
        row(List.of(node("s"), node("onThreshold")), rel("DEPENDS_ON", "s", "onThreshold", "2026-01-08T00:00:00Z", true)),
        row(List.of(node("s"), node("old")), rel("DEPENDS_ON", "s", "old", "2026-01-01T00:00:00Z", true)),
        row(List.of(node("s"), node("inactive")), rel("DEPENDS_ON", "s", "inactive", "2026-01-14T00:00:00Z", false)),
        row(List.of(node("s"), node("missing")), rel("DEPENDS_ON", "s", "missing", null, null)));

    var result = tool.traceDependencies(GID, null, null, null, null, null);

    var stale = result.relations().stream().collect(java.util.stream.Collectors.toMap(
        TraceDependenciesResult.RelationRef::to, TraceDependenciesResult.RelationRef::stale));
    assertThat(stale).containsEntry("fresh", false).containsEntry("onThreshold", false).containsEntry("old", true)
        .containsEntry("inactive", true).containsEntry("missing", true);
  }

  @Test
  void conflictMarkersAreCarriedOnTheNode() {
    var conflicted =
        node("c", "2026-01-14T00:00:00Z", List.of(Map.of("source", "SCM", "sourceType", "component", "sourceId", "x", "properties", List.of("name"))));
    returning("trace_downstream", false, row(List.of(node("s"), conflicted), rel("DEPENDS_ON", "s", "c")));

    var result = tool.traceDependencies(GID, null, null, null, null, null);

    assertThat(result.nodes().get(0).conflicts()).isEmpty();
    assertThat(result.nodes().get(1).conflicts())
        .containsExactly(new TraceDependenciesResult.Conflict("SCM", "component", "x", List.of("name")));
  }
}
