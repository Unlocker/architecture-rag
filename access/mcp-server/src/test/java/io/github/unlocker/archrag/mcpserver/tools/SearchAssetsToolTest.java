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

class SearchAssetsToolTest {

  private final GraphQueries queries = mock(GraphQueries.class);
  private final QueryLimits limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
  private SearchAssetsTool tool;

  @BeforeEach
  void setUp() {
    tool = new SearchAssetsTool(queries, limits);
  }

  private void returning(boolean truncated, Map<String, Object>... rows) {
    when(queries.execute(eq("search_assets"), any(), any()))
        .thenReturn(new QueryResult("search_assets", List.of(rows), truncated, rows.length, Duration.ZERO));
  }

  @Test
  @SuppressWarnings("unchecked")
  void passesAllFourParametersIncludingNullsAndLimitBudget() {
    returning(false);

    tool.searchAssets("  Foo AND (bar ", null, null, null);

    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<ResultBudget> budget = ArgumentCaptor.forClass(ResultBudget.class);
    verify(queries).execute(eq("search_assets"), params.capture(), budget.capture());
    assertThat(params.getValue())
        .containsOnlyKeys("query", "text", "types", "environment")
        .containsEntry("query", "Foo AND (bar")
        .containsEntry("text", "foo and \\(bar")
        .containsEntry("types", null)
        .containsEntry("environment", null);
    assertThat(budget.getValue().maxNodes()).isEqualTo(10);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsRowsAndPropagatesTruncated() {
    returning(
        true,
        Map.of(
            "gid", "g1", "type", "Service", "name", "Pay", "score", 1000.0, "matchType", "EXACT",
            "sources", List.of("EAM", "SCM"), "lastSeenAt", "2026-01-01T00:00:00Z"));

    SearchAssetsResult result = tool.searchAssets("g1", List.of("Service"), "prod", 5);

    assertThat(result.truncated()).isTrue();
    assertThat(result.items())
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.gid()).isEqualTo("g1");
              assertThat(h.type()).isEqualTo("Service");
              assertThat(h.score()).isEqualTo(1000.0);
              assertThat(h.matchType()).isEqualTo("EXACT");
              assertThat(h.sources()).containsExactly("EAM", "SCM");
            });
  }

  @Test
  void rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput() {
    assertThatThrownBy(() -> tool.searchAssets("  ", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.searchAssets(null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.searchAssets("x".repeat(201), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("xxxx");
    assertThatThrownBy(() -> tool.searchAssets("a", null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.searchAssets("a", null, null, 51))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.searchAssets("a", List.of("Bogus` DETACH DELETE n"), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("DETACH");
    verify(queries, never()).execute(any(), any(), any());
  }

  @Test
  void limitIsCappedByConfiguredNodeCeiling() {
    tool = new SearchAssetsTool(queries, new QueryLimits(6, 5, 50, Duration.ofSeconds(5), 1024));
    returning(false);

    tool.searchAssets("a", null, null, 50);

    ArgumentCaptor<ResultBudget> budget = ArgumentCaptor.forClass(ResultBudget.class);
    verify(queries).execute(any(), any(), budget.capture());
    assertThat(budget.getValue().maxNodes()).isEqualTo(5);
  }
}
