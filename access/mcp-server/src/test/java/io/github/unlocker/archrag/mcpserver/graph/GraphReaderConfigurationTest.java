package io.github.unlocker.archrag.mcpserver.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GraphReaderConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(UserConfigurations.of(GraphReaderConfiguration.class))
          .withPropertyValues(
              "archrag.neo4j.reader.uri=bolt://localhost:1",
              "archrag.neo4j.reader.username=mcp-reader",
              "archrag.neo4j.reader.password=s3cret",
              "archrag.query.limits.max-depth=4",
              "archrag.query.limits.max-nodes=7",
              "archrag.query.limits.max-paths=3",
              "archrag.query.limits.timeout=2s",
              "archrag.query.limits.max-response-bytes=1KB");

  @Test
  void limitsAndReaderPropertiesAreBound() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBean(QueryLimits.class))
              .isEqualTo(new QueryLimits(4, 7, 3, Duration.ofSeconds(2), 1024));
          assertThat(ctx.getBean(GraphReaderProperties.class).username()).isEqualTo("mcp-reader");
          // Драйвер ленивый: контекст поднимается без Neo4j.
          assertThat(ctx).hasSingleBean(GraphQueryExecutor.class);
        });
  }

  @Test
  void passwordIsNotPrinted() {
    runner.run(
        ctx ->
            assertThat(ctx.getBean(GraphReaderProperties.class).toString())
                .doesNotContain("s3cret"));
  }

  @Test
  void stalenessPrefixCoexistsWithLimitsAndDefaultsToSevenDays() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBean(StalenessProperties.class).staleAfter()).isEqualTo(Duration.ofDays(7));
          assertThat(ctx.getBean(QueryLimits.class).maxNodes()).isEqualTo(7);
        });
    runner
        .withPropertyValues("archrag.query.stale-after=PT1H")
        .run(ctx -> assertThat(ctx.getBean(StalenessProperties.class).staleAfter()).isEqualTo(Duration.ofHours(1)));
  }
}
