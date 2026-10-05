package io.github.unlocker.archrag.mcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import io.github.unlocker.archrag.mcpserver.graph.StalenessProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FindRuntimeFootprintToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";
  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
  private static final String FRESH = "2026-10-05T11:00:00Z";
  // Ровно на границе P7D — не stale.
  private static final String BOUNDARY = "2026-09-28T12:00:00Z";
  private static final String OLD = "2026-09-28T11:59:59Z";

  private final GraphQueries graph = mock(GraphQueries.class);
  private final FindRuntimeFootprintTool tool =
      new FindRuntimeFootprintTool(
          graph, new StalenessProperties(Duration.ofDays(7)), Clock.fixed(NOW, ZoneOffset.UTC));

  private static Map<String, Object> node(String gid, String seen, Map<String, Object> extra) {
    Map<String, Object> m = new HashMap<>(extra);
    m.put("gid", gid);
    m.put("lastSeenAt", seen);
    m.put(
        "sources",
        List.of(Map.of("source", "eam", "sourceType", "SYSTEM", "sourceId", "x", "authority", "MASTER")));
    return m;
  }

  private static Map<String, Object> edge(String validFrom) {
    Map<String, Object> m = new HashMap<>();
    m.put("source", "eam");
    m.put("sourceType", "REL");
    m.put("sourceId", "r1");
    m.put("validFrom", validFrom);
    return m;
  }

  private static Map<String, Object> row(
      Map<String, Object> system,
      Map<String, Object> svc,
      Map<String, Object> d,
      Map<String, Object> c,
      Map<String, Object> host) {
    Map<String, Object> m = new HashMap<>();
    m.put("system", system);
    m.put("service", svc);
    m.put("deployment", d);
    m.put("compute", c);
    m.put("host", host);
    return m;
  }

  private static QueryResult result(boolean truncated, Map<String, Object>... rows) {
    return new QueryResult("runtime_footprint", List.of(rows), truncated, rows.length, Duration.ZERO);
  }

  private static Map<String, Object> system(String seen) {
    return node(GID, seen, Map.of("name", "Billing"));
  }

  private static Map<String, Object> deployment(String gid, String seen) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("name", gid);
    extra.put("version", null);
    extra.put("status", "RUNNING");
    extra.put("environment", "PROD");
    extra.put("hasDeployment", edge(null));
    return node(gid, seen, extra);
  }

  private static Map<String, Object> compute(String gid, String type, String seen) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("type", type);
    extra.put("hostname", gid);
    extra.put("state", null);
    extra.put("runsOn", edge("2026-01-01T00:00:00Z"));
    return node(gid, seen, extra);
  }

  @Test
  void rejectsNonUuidWithoutEchoingInputAndWithoutQuery() {
    for (String bad : new String[] {"not-a-uuid-SECRET", "", null}) {
      assertThatThrownBy(() -> tool.findRuntimeFootprint(bad, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("systemGid must be a UUID");
    }
    verifyNoInteractions(graph);
  }

  @Test
  void noRowsMeansAssetNotFound() {
    when(graph.execute(any(), any(), any())).thenReturn(result(false));
    assertThatThrownBy(() -> tool.findRuntimeFootprint(GID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found");
  }

  @Test
  @SuppressWarnings("unchecked")
  void groupsRowsIntoServiceDeploymentComputeKeepingOrderAndHost() {
    Map<String, Object> svc = node("s1", FRESH, Map.of("name", "billing-api"));
    Map<String, Object> d1 = deployment("d1", FRESH);
    Map<String, Object> hostNode = node("ps1", FRESH, Map.of("hostname", "esx-1"));
    hostNode.put("state", null);
    hostNode.put("hostedOn", edge(null));
    when(graph.execute(eq("runtime_footprint"), any(), isNull()))
        .thenReturn(
            result(
                true,
                row(system(FRESH), svc, d1, compute("vm1", "VirtualMachine", FRESH), hostNode),
                row(system(FRESH), svc, d1, compute("ps1", "PhysicalServer", FRESH), null),
                row(system(FRESH), svc, deployment("d2", FRESH), null, null)));

    FindRuntimeFootprintResult r = tool.findRuntimeFootprint(GID, " PROD ");

    assertThat(r.environment()).isEqualTo("PROD");
    assertThat(r.truncated()).isTrue();
    assertThat(r.staleAfter()).isEqualTo("PT168H");
    assertThat(r.warnings()).isEmpty();
    assertThat(r.systemSources()).hasSize(1);
    assertThat(r.services()).hasSize(1);
    var deployments = r.services().get(0).deployments();
    assertThat(deployments).extracting(FindRuntimeFootprintResult.DeploymentFootprint::gid).containsExactly("d1", "d2");
    assertThat(deployments.get(0).hasDeployment().source()).isEqualTo("eam");
    var compute = deployments.get(0).compute();
    assertThat(compute).extracting(FindRuntimeFootprintResult.ComputeFootprint::gid).containsExactly("vm1", "ps1");
    assertThat(compute.get(0).hostedOn().hostname()).isEqualTo("esx-1");
    assertThat(compute.get(0).runsOn().validFrom()).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(compute.get(1).hostedOn()).isNull();
    assertThat(deployments.get(1).compute()).isEmpty();
    Map<String, Object> expected = new HashMap<>();
    expected.put("systemGid", GID);
    expected.put("environment", "PROD");
    verify(graph).execute(eq("runtime_footprint"), eq(expected), isNull());
  }

  @Test
  void serviceWithoutDeploymentsIsReturnedWithEmptyList() {
    Map<String, Object> svc = node("s1", FRESH, Map.of("name", "billing-api"));
    when(graph.execute(any(), any(), any()))
        .thenReturn(result(false, row(system(FRESH), svc, null, null, null)));

    FindRuntimeFootprintResult r = tool.findRuntimeFootprint(GID, "  ");

    assertThat(r.environment()).isNull();
    assertThat(r.services()).hasSize(1);
    assertThat(r.services().get(0).deployments()).isEmpty();
  }

  @Test
  void staleBoundaryAndNullLastSeenAtProduceFlagsAndWarnings() {
    Map<String, Object> svc = node("s1", BOUNDARY, Map.of("name", "billing-api"));
    Map<String, Object> dOld = deployment("d-old", OLD);
    Map<String, Object> dNull = deployment("d-null", null);
    when(graph.execute(any(), any(), any()))
        .thenReturn(
            result(
                false,
                row(system(OLD), svc, dOld, compute("c1", "ComputeInstance", FRESH), null),
                row(system(OLD), svc, dNull, null, null)));

    FindRuntimeFootprintResult r = tool.findRuntimeFootprint(GID, null);

    assertThat(r.systemStale()).isTrue();
    assertThat(r.services().get(0).stale()).isFalse();
    var ds = r.services().get(0).deployments();
    assertThat(ds.get(0).stale()).isTrue();
    assertThat(ds.get(1).stale()).isTrue();
    assertThat(ds.get(0).compute().get(0).stale()).isFalse();
    assertThat(r.warnings())
        .containsExactly(
            "stale: ITSystem " + GID + " lastSeenAt=" + OLD,
            "stale: Deployment d-old lastSeenAt=" + OLD,
            "stale: Deployment d-null lastSeenAt=null");
  }
}
