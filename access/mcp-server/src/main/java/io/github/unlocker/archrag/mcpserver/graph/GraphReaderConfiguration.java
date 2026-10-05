package io.github.unlocker.archrag.mcpserver.graph;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.DependencyTemplates;
import java.util.List;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственный драйвер Neo4j приложения (read-only креденшелы) и исполнитель шаблонов.
 *
 * <p>Autoconfig {@code spring.neo4j.*} не используется, чтобы reader и writer не смешались. Драйвер
 * создаётся лениво: соединение открывается при первом запросе. Шаблоны F1–F4 добавляются как бины
 * {@link QueryTemplate}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GraphReaderProperties.class, GraphReaderProperties.Limits.class})
public class GraphReaderConfiguration {

  @Bean(destroyMethod = "close")
  Driver graphReaderDriver(GraphReaderProperties properties) {
    return GraphDatabase.driver(
        properties.uri(), AuthTokens.basic(properties.username(), properties.password()));
  }

  @Bean
  QueryLimits queryLimits(GraphReaderProperties.Limits limits) {
    return new QueryLimits(
        limits.maxDepth(),
        limits.maxNodes(),
        limits.maxPaths(),
        limits.timeout(),
        limits.maxResponseBytes().toBytes());
  }

  @Bean
  QueryTemplate searchAssetsTemplate() {
    return AssetTemplates.SEARCH_ASSETS;
  }

  @Bean
  QueryTemplate getAssetTemplate() {
    return AssetTemplates.GET_ASSET;
  }

  @Bean
  QueryTemplate assetRelationsTemplate() {
    return AssetTemplates.ASSET_RELATIONS;
  }

  @Bean
  QueryTemplate traceDownstreamTemplate() {
    return DependencyTemplates.TRACE_DOWNSTREAM;
  }

  @Bean
  QueryTemplate traceUpstreamTemplate() {
    return DependencyTemplates.TRACE_UPSTREAM;
  }

  @Bean
  QueryTemplateRegistry queryTemplateRegistry(List<QueryTemplate> templates, QueryLimits limits) {
    return QueryTemplateRegistry.of(templates, limits);
  }

  @Bean
  GraphQueryExecutor graphQueryExecutor(Driver graphReaderDriver, QueryTemplateRegistry registry) {
    return new GraphQueryExecutor(graphReaderDriver, registry);
  }
}
