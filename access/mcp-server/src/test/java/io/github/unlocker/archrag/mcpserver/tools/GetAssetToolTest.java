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
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetAssetToolTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  private final GraphQueries queries = mock(GraphQueries.class);
  private final QueryLimits limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
  private GetAssetTool tool;

  @BeforeEach
  void setUp() {
    tool = new GetAssetTool(queries, limits);
  }

  private static QueryResult result(String id, boolean truncated, List<Map<String, Object>> rows) {
    return new QueryResult(id, rows, truncated, rows.size(), Duration.ZERO);
  }

  private void card(Map<String, Object> properties) {
    Map<String, Object> row = new java.util.HashMap<>();
    row.put("gid", GID);
    row.put("type", "Service");
    row.put("properties", properties);
    row.put("firstSeenAt", "2026-01-01T00:00:00Z");
    row.put("lastSeenAt", "2026-01-02T00:00:00Z");
    row.put("deletedAt", null);
    row.put("isCurrent", true);
    row.put(
        "sources",
        List.of(
            Map.of(
                "source", "EAM", "sourceType", "component", "sourceId", "EAM-1", "sourceVersion", "1",
                "fetchedAt", "2026-01-01T00:00:00Z", "active", true, "authority", "MASTER")));
    when(queries.execute(eq("get_asset"), any(), any())).thenReturn(result("get_asset", false, List.of(row)));
  }

  private void relations(boolean truncated) {
    when(queries.execute(eq("asset_relations"), any(), any()))
        .thenReturn(
            result(
                "asset_relations",
                truncated,
                List.of(
                    Map.of(
                        "relationType", "HAS_DEPLOYMENT", "direction", "OUT", "gid", "g2", "type", "Deployment",
                        "name", "dep", "isCurrent", true, "validFrom", "2026-01-01T00:00:00Z"))));
  }

  @Test
  void mapsCardWithSourcesAndRelations() {
    card(Map.of("name", "Pay"));
    relations(false);

    GetAssetResult r = tool.getAsset(GID, null);

    assertThat(r.gid()).isEqualTo(GID);
    assertThat(r.isCurrent()).isTrue();
    assertThat(r.deletedAt()).isNull();
    assertThat(r.properties()).containsEntry("name", "Pay");
    assertThat(r.sources()).singleElement().satisfies(s -> {
      assertThat(s.authority()).isEqualTo("MASTER");
      assertThat(s.active()).isTrue();
    });
    assertThat(r.relations()).singleElement().satisfies(x -> {
      assertThat(x.gid()).isEqualTo("g2");
      assertThat(x.direction()).isEqualTo("OUT");
    });
    assertThat(r.truncated()).isFalse();
  }

  @Test
  void rejectsNonUuidWithoutEchoingInputOrTouchingGraph() {
    assertThatThrownBy(() -> tool.getAsset("secret-not-uuid", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("gid must be a UUID");
    assertThatThrownBy(() -> tool.getAsset(null, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.getAsset("", null)).isInstanceOf(IllegalArgumentException.class);
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  void emptyResultIsAssetNotFoundWithoutGid() {
    when(queries.execute(eq("get_asset"), any(), any()))
        .thenReturn(result("get_asset", false, List.of()));

    assertThatThrownBy(() -> tool.getAsset(GID, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found")
        .hasMessageNotContaining(GID);
    verify(queries, never()).execute(eq("asset_relations"), any(), any());
  }

  @Test
  void includeRelationsFalseSkipsRelationsTemplate() {
    card(Map.of());

    GetAssetResult r = tool.getAsset(GID, false);

    assertThat(r.relations()).isEmpty();
    assertThat(r.truncated()).isFalse();
    verify(queries, never()).execute(eq("asset_relations"), any(), any());
  }

  @Test
  void temporalPropertiesBecomeIsoStringsAndServiceKeysAreDropped() {
    var at = ZonedDateTime.of(2026, 1, 3, 10, 0, 0, 0, ZoneOffset.UTC);
    card(
        Map.of(
            "gid", GID, "isCurrent", true, "firstSeenAt", at, "lastSeenAt", at, "deletedAt", at,
            "name", "Pay", "reviewedAt", at, "history", List.of(at)));

    GetAssetResult r = tool.getAsset(GID, false);

    assertThat(r.properties())
        .containsOnlyKeys("name", "reviewedAt", "history")
        .containsEntry("reviewedAt", "2026-01-03T10:00:00Z")
        .containsEntry("history", List.of("2026-01-03T10:00:00Z"));
  }

  @Test
  void truncatedComesFromRelationsQuery() {
    card(Map.of());
    relations(true);

    assertThat(tool.getAsset(GID, true).truncated()).isTrue();
  }
}
