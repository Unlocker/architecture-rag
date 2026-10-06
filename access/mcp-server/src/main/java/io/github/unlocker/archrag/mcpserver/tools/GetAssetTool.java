package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code get_asset}: карточка актива, записи источников и связи 1 уровня. */
@Component
public class GetAssetTool {

  /** Ключи узла, которые идут полями верхнего уровня и не дублируются в {@code properties}. */
  private static final Set<String> SERVICE_KEYS =
      Set.of("gid", "isCurrent", "firstSeenAt", "lastSeenAt", "deletedAt");

  private final GraphQueries queries;
  private final QueryLimits limits;

  GetAssetTool(GraphQueries queries, QueryLimits limits) {
    this.queries = queries;
    this.limits = limits;
  }

  /**
   * Возвращает карточку актива, в том числе закрытого ({@code isCurrent=false}).
   *
   * @throws IllegalArgumentException {@code gid} не UUID или актив не найден; значение {@code gid} в
   *     текст ошибки не попадает
   */
  @McpTool(
      name = "get_asset",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true,
              openWorldHint = false),
      description =
          "Карточка архитектурного актива по gid: свойства, firstSeenAt/lastSeenAt/isCurrent, записи"
              + " источников и действующие связи 1 уровня (gid соседей можно передавать в следующие"
              + " вызовы). Закрытый актив возвращается с isCurrent=false.")
  public GetAssetResult getAsset(
      @McpToolParam(description = "gid актива (UUID)") String gid,
      @McpToolParam(description = "Включить связи 1 уровня, по умолчанию true", required = false)
          Boolean includeRelations) {
    String normalized = normalizeGid(gid);
    Map<String, Object> params = Map.of("gid", normalized);
    ResultBudget budget =
        new ResultBudget(
            limits.maxDepth(),
            limits.maxNodes(),
            limits.maxPaths(),
            limits.timeout(),
            limits.maxResponseBytes());

    QueryResult card = queries.execute(AssetTemplates.GET_ASSET.id(), params, budget);
    if (card.rows().isEmpty()) {
      throw new IllegalArgumentException("asset not found");
    }
    Map<String, Object> row = card.rows().getFirst();

    List<GetAssetResult.RelationRef> relations = List.of();
    boolean truncated = false;
    if (includeRelations == null || includeRelations) {
      QueryResult related = queries.execute(AssetTemplates.ASSET_RELATIONS.id(), params, budget);
      relations = related.rows().stream().map(GetAssetTool::relation).toList();
      truncated = related.truncated();
    }
    return new GetAssetResult(
        (String) row.get("gid"),
        (String) row.get("type"),
        properties(row),
        (String) row.get("firstSeenAt"),
        (String) row.get("lastSeenAt"),
        (String) row.get("deletedAt"),
        Boolean.TRUE.equals(row.get("isCurrent")),
        sources(row),
        relations,
        truncated);
  }

  private static String normalizeGid(String gid) {
    try {
      return UUID.fromString(gid == null ? "" : gid.strip()).toString();
    } catch (IllegalArgumentException e) {
      // Ввод не эхоим: сообщение исходного исключения содержит значение.
      throw new IllegalArgumentException("gid must be a UUID");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    ((Map<String, Object>) row.get("properties"))
        .forEach(
            (k, v) -> {
              if (!SERVICE_KEYS.contains(k)) {
                out.put(k, jsonValue(v));
              }
            });
    return out;
  }

  /** Время в ISO-8601 UTC, как {@code lastSeenAt} у {@code search_assets}; списки обходятся рекурсивно. */
  private static Object jsonValue(Object value) {
    return switch (value) {
      case ZonedDateTime z -> z.toInstant().toString();
      case OffsetDateTime o -> o.toInstant().toString();
      case TemporalAccessor t -> t.toString();
      case List<?> list -> list.stream().map(GetAssetTool::jsonValue).toList();
      case null, default -> value;
    };
  }

  @SuppressWarnings("unchecked")
  private static List<GetAssetResult.SourceRef> sources(Map<String, Object> row) {
    return ((List<Map<String, Object>>) row.get("sources"))
        .stream()
            .map(
                s ->
                    new GetAssetResult.SourceRef(
                        (String) s.get("source"),
                        (String) s.get("sourceType"),
                        (String) s.get("sourceId"),
                        (String) s.get("sourceVersion"),
                        (String) s.get("fetchedAt"),
                        Boolean.TRUE.equals(s.get("active")),
                        (String) s.get("authority")))
            .toList();
  }

  private static GetAssetResult.RelationRef relation(Map<String, Object> r) {
    return new GetAssetResult.RelationRef(
        (String) r.get("relationType"),
        (String) r.get("direction"),
        (String) r.get("gid"),
        (String) r.get("type"),
        (String) r.get("name"),
        Boolean.TRUE.equals(r.get("isCurrent")),
        (String) r.get("validFrom"));
  }
}
