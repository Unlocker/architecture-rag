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
}
