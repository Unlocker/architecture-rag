package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.DependencyTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code trace_dependencies}: обход графа вверх или вниз по разрешённым типам связей. */
@Component
public class TraceDependenciesTool {

  private static final String DOWNSTREAM = "downstream";
  private static final String UPSTREAM = "upstream";
  private static final String MODE_TRACE = "trace";

  private final GraphQueries queries;
  private final QueryLimits limits;

  TraceDependenciesTool(GraphQueries queries, QueryLimits limits) {
    this.queries = queries;
    this.limits = limits;
  }

  /**
   * Трассирует зависимости актива в режиме {@code trace}.
   *
   * @throws IllegalArgumentException {@code gid} не UUID или актив не найден, неизвестное
   *     направление, {@code maxDepth} вне 1..лимит, тип связи вне allowlist, режим не {@code trace};
   *     значения ввода в текст ошибки не попадают
   */
  @McpTool(
      name = "trace_dependencies",
      description =
          "Трассировка зависимостей актива по gid: upstream идёт против направления связей,"
              + " downstream по направлению. Только действующие связи из allowlist"
              + " (DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON, HOSTED_ON, OWNED_BY) и текущие узлы."
              + " Возвращает узлы, связи и пути; truncated=true, если путей больше лимита.")
  public TraceDependenciesResult traceDependencies(
      @McpToolParam(description = "gid стартового актива (UUID)") String gid,
      @McpToolParam(description = "upstream или downstream, по умолчанию downstream", required = false)
          String direction,
      @McpToolParam(description = "Глубина обхода, от 1 до лимита сервера (по умолчанию лимит)", required = false)
          Integer maxDepth,
      @McpToolParam(description = "Типы связей из allowlist, по умолчанию все", required = false)
          List<String> relationTypes,
      @McpToolParam(description = "Режим: только trace", required = false) String mode) {
    String normalizedGid = normalizeGid(gid);
    if (mode != null && !mode.isBlank() && !MODE_TRACE.equals(mode.strip().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(
          "impact".equalsIgnoreCase(mode.strip()) ? "mode impact is not supported yet" : "mode must be trace");
    }
    String dir = direction == null || direction.isBlank() ? DOWNSTREAM : direction.strip().toLowerCase(Locale.ROOT);
    if (!dir.equals(DOWNSTREAM) && !dir.equals(UPSTREAM)) {
      throw new IllegalArgumentException("direction must be upstream or downstream");
    }
    int depth = maxDepth == null ? limits.maxDepth() : maxDepth;
    if (depth < 1 || depth > limits.maxDepth()) {
      throw new IllegalArgumentException("maxDepth must be in 1.." + limits.maxDepth());
    }
    List<String> types = validRelationTypes(relationTypes);

    var template = dir.equals(DOWNSTREAM) ? DependencyTemplates.TRACE_DOWNSTREAM : DependencyTemplates.TRACE_UPSTREAM;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("gid", normalizedGid);
    params.put("relationTypes", types);
    ResultBudget budget =
        new ResultBudget(depth, limits.maxNodes(), limits.maxPaths(), limits.timeout(), limits.maxResponseBytes());
    QueryResult result = queries.execute(template.id(), params, budget);

    if (result.rows().isEmpty() && !exists(normalizedGid, budget)) {
      throw new IllegalArgumentException("asset not found");
    }
    return assemble(normalizedGid, dir, result);
  }

  /** Пустой обход у существующего узла без связей легитимен; у несуществующего gid это ошибка. */
  private boolean exists(String gid, ResultBudget budget) {
    return !queries.execute(AssetTemplates.GET_ASSET.id(), Map.of("gid", gid), budget).rows().isEmpty();
  }

  private static String normalizeGid(String gid) {
    try {
      return UUID.fromString(gid == null ? "" : gid.strip()).toString();
    } catch (IllegalArgumentException e) {
      // Ввод не эхоим: сообщение исходного исключения содержит значение.
      throw new IllegalArgumentException("gid must be a UUID");
    }
  }

  private static List<String> validRelationTypes(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return DependencyTemplates.TRACE_RELATION_TYPES;
    }
    var allowed = Set.copyOf(DependencyTemplates.TRACE_RELATION_TYPES);
    for (String type : requested) {
      if (type == null || !allowed.contains(type)) {
        throw new IllegalArgumentException(
            "Unknown relation type; allowed: " + String.join(", ", DependencyTemplates.TRACE_RELATION_TYPES));
      }
    }
    return List.copyOf(requested);
  }

  @SuppressWarnings("unchecked")
  private static TraceDependenciesResult assemble(String gid, String direction, QueryResult result) {
    Map<String, TraceDependenciesResult.NodeRef> nodes = new LinkedHashMap<>();
    Map<TraceDependenciesResult.RelationKey, TraceDependenciesResult.RelationRef> relations = new LinkedHashMap<>();
    List<TraceDependenciesResult.TracePath> paths = new ArrayList<>();
    for (Map<String, Object> row : result.rows()) {
      List<String> pathNodes = new ArrayList<>();
      for (Map<String, Object> n : (List<Map<String, Object>>) row.get("nodes")) {
        String nodeGid = (String) n.get("gid");
        pathNodes.add(nodeGid);
        nodes.putIfAbsent(
            nodeGid,
            new TraceDependenciesResult.NodeRef(
                nodeGid, (String) n.get("label"), (String) n.get("name"), (String) n.get("lastSeenAt")));
      }
      List<TraceDependenciesResult.RelationKey> pathRelations = new ArrayList<>();
      for (Map<String, Object> r : (List<Map<String, Object>>) row.get("relations")) {
        var key = new TraceDependenciesResult.RelationKey((String) r.get("type"), (String) r.get("from"), (String) r.get("to"));
        pathRelations.add(key);
        relations.putIfAbsent(
            key,
            new TraceDependenciesResult.RelationRef(
                key.type(), key.from(), key.to(), (String) r.get("validFrom"),
                (String) r.get("assertedBySource"), (String) r.get("assertedByType"), (String) r.get("assertedById")));
      }
      paths.add(new TraceDependenciesResult.TracePath(pathNodes, pathRelations));
    }
    return new TraceDependenciesResult(
        gid, direction, List.copyOf(nodes.values()), List.copyOf(relations.values()), paths, result.truncated());
  }
}
