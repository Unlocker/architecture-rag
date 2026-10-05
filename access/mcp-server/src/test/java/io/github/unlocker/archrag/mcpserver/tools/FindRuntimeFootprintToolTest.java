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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FindRuntimeFootprintToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  private final GraphQueries graph = mock(GraphQueries.class);
  private final FindRuntimeFootprintTool tool = new FindRuntimeFootprintTool(graph);

  private static QueryResult result(boolean truncated, Map<String, Object>... rows) {
    return new QueryResult("find_runtime_footprint", List.of(rows), truncated, rows.length, Duration.ZERO);
  }

  private static Map<String, Object> row(Object gid, List<Object> instances) {
    Map<String, Object> m = new HashMap<>();
    m.put("systemGid", GID);
    m.put("systemName", "Billing");
    m.put("systemIsCurrent", true);
    m.put("gid", gid);
    m.put("name", "billing-prod-1");
    m.put("version", null);
    m.put("serviceGid", "s1");
    m.put("serviceName", "billing-api");
    m.put("environment", "PROD");
    m.put("instances", instances);
    return m;
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
  void noRowsMeansSystemNotFoundWithoutEchoingInput() {
    when(graph.execute(any(), any(), any())).thenReturn(result(false));
    assertThatThrownBy(() -> tool.findRuntimeFootprint(GID, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("system not found");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsRowsIncludingNullHostedOnAndEmptyInstances() {
    Map<String, Object> vm =
        Map.of(
            "gid", "vm1",
            "type", "VirtualMachine",
            "hostname", "vm-1",
            "state", "RUNNING",
            "hostedOn", Map.of("gid", "ps1", "hostname", "esx-1", "serialNumber", "SN1"));
    Map<String, Object> bare = new HashMap<>();
    bare.put("gid", "ci1");
    bare.put("type", "ComputeInstance");
    bare.put("hostname", "h");
    bare.put("state", null);
    bare.put("hostedOn", null);
    when(graph.execute(eq("find_runtime_footprint"), any(), isNull()))
        .thenReturn(result(true, row("d1", List.of(vm, bare)), row("d2", List.of())));

    FindRuntimeFootprintResult r = tool.findRuntimeFootprint(GID, " PROD ");

    assertThat(r.systemName()).isEqualTo("Billing");
    assertThat(r.isCurrent()).isTrue();
    assertThat(r.truncated()).isTrue();
    assertThat(r.deployments()).hasSize(2);
    var first = r.deployments().get(0);
    assertThat(first.version()).isNull();
    assertThat(first.instances()).hasSize(2);
    assertThat(first.instances().get(0).hostedOn().serialNumber()).isEqualTo("SN1");
    assertThat(first.instances().get(1).hostedOn()).isNull();
    assertThat(r.deployments().get(1).instances()).isEmpty();
    verify(graph)
        .execute(
            eq("find_runtime_footprint"),
            eq(Map.of("systemGid", GID, "environment", "PROD")),
            isNull());
  }

  @Test
  void blankEnvironmentMeansNoFilterAndClosedSystemGivesEmptyFootprint() {
    Map<String, Object> closed = row(null, List.of());
    closed.put("systemIsCurrent", false);
    when(graph.execute(any(), any(), any())).thenReturn(result(false, closed));

    FindRuntimeFootprintResult r = tool.findRuntimeFootprint(GID, "  ");

    assertThat(r.isCurrent()).isFalse();
    assertThat(r.deployments()).isEmpty();
    Map<String, Object> expected = new HashMap<>();
    expected.put("systemGid", GID);
    expected.put("environment", null);
    verify(graph).execute(any(), eq(expected), isNull());
  }
}
