package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetTemplatesTest {

  @Test
  void searchTemplateIsValidAndRegistrable() {
    var limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
    var registry = QueryTemplateRegistry.of(List.of(AssetTemplates.SEARCH_ASSETS), limits);

    assertThat(registry.find("search_assets")).isPresent();
    assertThat(AssetTemplates.SEARCH_ASSETS.parameters())
        .containsExactlyInAnyOrder("query", "text", "types", "environment");
    assertThat(AssetTemplates.SEARCH_ASSETS.maxLiteralDepth()).isZero();
    assertThat(AssetTemplates.SEARCH_ASSETS.hasDepthPlaceholder()).isFalse();
  }

  @Test
  void getAssetTemplatesAreValidAndRegistrable() {
    var limits = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);
    var registry =
        QueryTemplateRegistry.of(List.of(AssetTemplates.GET_ASSET, AssetTemplates.ASSET_RELATIONS), limits);

    assertThat(registry.find("get_asset")).isPresent();
    assertThat(registry.find("asset_relations")).isPresent();
    assertThat(AssetTemplates.GET_ASSET.parameters()).containsExactly("gid");
    assertThat(AssetTemplates.ASSET_RELATIONS.parameters()).containsExactly("gid");
    assertThat(AssetTemplates.GET_ASSET.cypher()).doesNotContain("isCurrent = true");
    assertThat(AssetTemplates.ASSET_RELATIONS.cypher()).contains("rel.validTo IS NULL");
  }

  @Test
  void relationAllowlistExcludesServiceRelations() {
    assertThat(AssetTemplates.RELATION_TYPES)
        .containsExactly(
            "DECOMPOSED_INTO", "IMPLEMENTED_IN", "HAS_DEPLOYMENT", "IN_ENVIRONMENT", "RUNS_ON", "DEPENDS_ON", "OWNED_BY")
        .doesNotContain("ASSERTS", "OWNS_RECORD", "PROCESSED", "PART_OF", "HOSTED_ON");
    assertThat(AssetTemplates.ASSET_RELATIONS.cypher()).doesNotContain("ASSERTS");
  }
}
