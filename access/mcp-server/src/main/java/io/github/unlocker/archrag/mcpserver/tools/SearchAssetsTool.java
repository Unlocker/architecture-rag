package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.FulltextQuery;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code search_assets}: точное совпадение по gid/sourceId, затем полнотекстовый поиск. */
@Component
public class SearchAssetsTool {

  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 50;
  static final int MAX_QUERY_LENGTH = 200;

  private final GraphQueries queries;
  private final QueryLimits limits;

  SearchAssetsTool(GraphQueries queries, QueryLimits limits) {
    this.queries = queries;
    this.limits = limits;
  }

  /**
   * Ищет актуальные активы.
   *
   * @throws IllegalArgumentException пустой или слишком длинный {@code query}, {@code limit} вне
   *     1..50, неизвестная метка в {@code types}
   */
  @McpTool(
      name = "search_assets",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true,
              openWorldHint = false),
      description =
          "Поиск архитектурных активов. Сначала точное совпадение по gid или sourceId, затем полнотекстовый"
              + " поиск по имени и описанию (только ITSystem и Service). Остальные типы находятся"
              + " только точным совпадением. Возвращает только актуальные активы.")
  public SearchAssetsResult searchAssets(
      @McpToolParam(description = "gid, sourceId или текст для поиска (до 200 символов)") String query,
      @McpToolParam(
              description =
                  "Фильтр по типам: ITSystem, Service, Repository, Team, Environment, Deployment,"
                      + " ComputeInstance",
              required = false)
          List<String> types,
      @McpToolParam(description = "Фильтр по коду окружения (Environment.code)", required = false)
          String environment,
      @McpToolParam(description = "Максимум результатов, 1..50, по умолчанию 10", required = false)
          Integer limit) {
    String trimmed = query == null ? "" : query.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (trimmed.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("query is longer than " + MAX_QUERY_LENGTH + " characters");
    }
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
    if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be in 1.." + MAX_LIMIT);
    }
    List<String> typeFilter = validTypes(types);
    String env = environment == null || environment.isBlank() ? null : environment.strip();

    // Параметры шаблона передаются все, включая null: исполнитель требует полный набор ключей.
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("query", trimmed);
    params.put("text", FulltextQuery.escape(trimmed));
    params.put("types", typeFilter);
    params.put("environment", env);

    ResultBudget budget =
        new ResultBudget(
            limits.maxDepth(),
            Math.min(effectiveLimit, limits.maxNodes()),
            limits.maxPaths(),
            limits.timeout(),
            limits.maxResponseBytes());
    QueryResult result = queries.execute(AssetTemplates.SEARCH_ASSETS.id(), params, budget);
    return new SearchAssetsResult(result.rows().stream().map(SearchAssetsTool::hit).toList(), result.truncated());
  }

  private static List<String> validTypes(List<String> types) {
    if (types == null || types.isEmpty()) {
      return null;
    }
    var allowed = new HashSet<>(AssetTemplates.SEARCHABLE_TYPES);
    for (String type : types) {
      if (type == null || !allowed.contains(type)) {
        throw new IllegalArgumentException(
            "Unknown asset type; allowed: " + String.join(", ", AssetTemplates.SEARCHABLE_TYPES));
      }
    }
    return List.copyOf(types);
  }

  @SuppressWarnings("unchecked")
  private static SearchAssetsResult.AssetHit hit(Map<String, Object> row) {
    return new SearchAssetsResult.AssetHit(
        (String) row.get("gid"),
        (String) row.get("type"),
        (String) row.get("name"),
        ((Number) row.get("score")).doubleValue(),
        (String) row.get("matchType"),
        (List<String>) row.get("sources"),
        (String) row.get("lastSeenAt"));
  }
}
