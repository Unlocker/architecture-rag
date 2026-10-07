package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsoleTemplatesTest {

  private static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  @Test
  void allTemplatesAreValidAndRegistrableAlongsideAssetTemplates() {
    List<QueryTemplate> all = new ArrayList<>(ConsoleTemplates.ALL);
    all.add(AssetTemplates.SEARCH_ASSETS);
    all.add(AssetTemplates.GET_ASSET);
    all.add(AssetTemplates.EXPLAIN_PROVENANCE);

    var registry = QueryTemplateRegistry.of(all, LIMITS);

    assertThat(registry.ids())
        .contains(
            "graph_stats_nodes",
            "graph_stats_relations",
            "graph_last_update",
            "console_neighborhood_nodes",
            "console_neighborhood_edges");
  }

  @Test
  void neighborhoodNodesUsesBoundedDepthPlaceholderOnly() {
    assertThat(ConsoleTemplates.NEIGHBORHOOD_NODES.hasDepthPlaceholder()).isTrue();
    assertThat(ConsoleTemplates.NEIGHBORHOOD_NODES.parameters()).containsExactlyInAnyOrder("gid", "relTypes");
    assertThat(ConsoleTemplates.NEIGHBORHOOD_EDGES.hasDepthPlaceholder()).isFalse();
    assertThat(ConsoleTemplates.NEIGHBORHOOD_NODES.render(2, 6)).contains("*1..2]").doesNotContain("{maxDepth}");
  }

  @Test
  void templatesContainNoWriteClausesAndNoServiceRelationsInNeighborhood() {
    for (QueryTemplate template : ConsoleTemplates.ALL) {
      assertThat(template.cypher().toUpperCase())
          .doesNotContain("CREATE ", "MERGE ", " SET ", "DELETE ", "REMOVE ");
    }
    assertThat(ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES)
        .contains("DEPENDS_ON", "HOSTED_ON")
        .doesNotContain("ASSERTS", "OWNS_RECORD");
  }

  @Test
  void statsCoverEveryAllowlistedLabelAndRelationType() {
    for (String label : ConsoleTemplates.STATS_LABELS) {
      assertThat(ConsoleTemplates.GRAPH_STATS_NODES.cypher()).contains("MATCH (n:" + label + ")");
    }
    for (String type : ConsoleTemplates.STATS_RELATION_TYPES) {
      assertThat(ConsoleTemplates.GRAPH_STATS_RELATIONS.cypher()).contains("[r:" + type + "]");
    }
  }
}
