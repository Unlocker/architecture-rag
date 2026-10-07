package io.github.unlocker.archrag.adminconsole.graph;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.ConsoleTemplates;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственный драйвер Neo4j консоли (read-only креденшелы) и исполнитель шаблонов.
 *
 * <p>Autoconfig {@code spring.neo4j.*} не используется, чтобы reader и writer не смешались. Драйвер
 * создаётся лениво: соединение открывается при первом запросе. В реестре только шаблоны чтения.
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

  /** Шаблоны MCP (поиск, карточка, происхождение) переиспользуются как есть, остальные — из {@link ConsoleTemplates}. */
  @Bean
  QueryTemplateRegistry queryTemplateRegistry(QueryLimits limits) {
    List<QueryTemplate> templates = new ArrayList<>(ConsoleTemplates.ALL);
    templates.add(AssetTemplates.SEARCH_ASSETS);
    templates.add(AssetTemplates.GET_ASSET);
    templates.add(AssetTemplates.EXPLAIN_PROVENANCE);
    return QueryTemplateRegistry.of(templates, limits);
  }

  @Bean
  GraphQueryExecutor graphQueryExecutor(Driver graphReaderDriver, QueryTemplateRegistry registry) {
    return new GraphQueryExecutor(graphReaderDriver, registry);
  }
}
