package io.github.unlocker.archrag.adminconsole.api;

import io.github.unlocker.archrag.adminconsole.api.GraphResponses.AssetHit;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.Count;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.GraphEdge;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.GraphNode;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.Neighborhood;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.NodeCard;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.SearchResponse;
import io.github.unlocker.archrag.adminconsole.api.GraphResponses.Stats;
import io.github.unlocker.archrag.adminconsole.graph.GraphReads;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.ConsoleTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.FulltextQuery;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API графа для админ-консоли. Каждый эндпоинт исполняет только именованные шаблоны через
 * {@link GraphReads}; Cypher из запроса не собирается.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 50;
  static final int MAX_QUERY_LENGTH = 200;
  /** Верхняя граница глубины окрестности; потолок конфигурации может быть выше. */
  static final int MAX_DEPTH = 2;

  private static final Set<String> SERVICE_KEYS =
      Set.of("gid", "isCurrent", "firstSeenAt", "lastSeenAt", "deletedAt");

  private final GraphReads reads;

  GraphController(GraphReads reads) {
    this.reads = reads;
  }

  /** Число узлов по меткам, связей по типам и время последнего обновления. */
  @GetMapping("/stats")
  public Stats stats() {
    ResultBudget budget = reads.ceilings();
    QueryResult nodes = reads.execute(ConsoleTemplates.GRAPH_STATS_NODES.id(), Map.of(), budget);
    QueryResult relations = reads.execute(ConsoleTemplates.GRAPH_STATS_RELATIONS.id(), Map.of(), budget);
    QueryResult updated = reads.execute(ConsoleTemplates.GRAPH_LAST_UPDATE.id(), Map.of(), budget);
    String lastUpdatedAt =
        updated.rows().isEmpty() ? null : (String) updated.rows().getFirst().get("lastUpdatedAt");
    return new Stats(counts(nodes, "label"), counts(relations, "type"), lastUpdatedAt);
  }

  /** Поиск: точное совпадение по gid/sourceId, затем полнотекстовый (как tool {@code search_assets}). */
  @GetMapping("/search")
  public SearchResponse search(
      @RequestParam("q") String q,
      @RequestParam(name = "types", required = false) List<String> types,
      @RequestParam(name = "limit", required = false) Integer limit) {
    String query = q == null ? "" : q.strip();
    if (query.isEmpty()) {
      throw new InvalidRequestException("q must not be blank");
    }
    if (query.length() > MAX_QUERY_LENGTH) {
      throw new InvalidRequestException("q is longer than " + MAX_QUERY_LENGTH + " characters");
    }
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
    if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
      throw new InvalidRequestException("limit must be in 1.." + MAX_LIMIT);
    }
    List<String> typeFilter = allowed(types, AssetTemplates.SEARCHABLE_TYPES, "type");

    // Параметры шаблона передаются все, включая null: исполнитель требует полный набор ключей.
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("query", query);
    params.put("text", FulltextQuery.escape(query));
    params.put("types", typeFilter);
    params.put("environment", null);
    ResultBudget ceilings = reads.ceilings();
    ResultBudget budget =
        new ResultBudget(
            ceilings.maxDepth(),
            Math.min(effectiveLimit, ceilings.maxNodes()),
            ceilings.maxPaths(),
            ceilings.timeout(),
            ceilings.maxResponseBytes());
    QueryResult result = reads.execute(AssetTemplates.SEARCH_ASSETS.id(), params, budget);
    return new SearchResponse(result.rows().stream().map(GraphController::hit).toList(), result.truncated());
  }

  /** Карточка актива: свойства, {@code firstSeenAt}/{@code lastSeenAt}/{@code isCurrent}, записи источников. */
  @GetMapping("/nodes/{gid}")
  public NodeCard node(@PathVariable("gid") String gid) {
    Map<String, Object> params = Map.of("gid", normalizeGid(gid));
    ResultBudget budget = reads.ceilings();
    QueryResult card = reads.execute(AssetTemplates.GET_ASSET.id(), params, budget);
    if (card.rows().isEmpty()) {
      throw new NotFoundException("asset not found");
    }
    Map<String, Object> row = card.rows().getFirst();
    QueryResult provenance = reads.execute(AssetTemplates.EXPLAIN_PROVENANCE.id(), params, budget);
    return new NodeCard(
        (String) row.get("gid"),
        (String) row.get("type"),
        properties(row),
        (String) row.get("firstSeenAt"),
        (String) row.get("lastSeenAt"),
        (String) row.get("deletedAt"),
        Boolean.TRUE.equals(row.get("isCurrent")),
        records(provenance));
  }

  /** Окрестность узла глубиной 1 или 2 в пределах бюджета узлов; при превышении {@code truncated=true}. */
  @GetMapping("/nodes/{gid}/neighborhood")
  public Neighborhood neighborhood(
      @PathVariable("gid") String gid,
      @RequestParam(name = "depth", required = false) Integer depth,
      @RequestParam(name = "relTypes", required = false) List<String> relTypes) {
    String normalized = normalizeGid(gid);
    int effectiveDepth = depth == null ? 1 : depth;
    if (effectiveDepth < 1 || effectiveDepth > MAX_DEPTH) {
      throw new InvalidRequestException("depth must be in 1.." + MAX_DEPTH);
    }
    List<String> types = allowed(relTypes, ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES, "relation type");
    if (types == null) {
      types = ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES;
    }

    ResultBudget ceilings = reads.ceilings();
    // Глубина попадает в шаблон проверенным int; потолок конфигурации режет её дополнительно.
    ResultBudget budget =
        new ResultBudget(
            effectiveDepth,
            ceilings.maxNodes(),
            ceilings.maxPaths(),
            ceilings.timeout(),
            ceilings.maxResponseBytes());
    QueryResult nodes =
        reads.execute(
            ConsoleTemplates.NEIGHBORHOOD_NODES.id(), Map.of("gid", normalized, "relTypes", types), budget);
    if (nodes.rows().isEmpty()) {
      throw new NotFoundException("asset not found");
    }
    List<GraphNode> graphNodes = nodes.rows().stream().map(GraphController::graphNode).toList();
    List<String> gids = graphNodes.stream().map(GraphNode::gid).toList();
    QueryResult edges =
        reads.execute(
            ConsoleTemplates.NEIGHBORHOOD_EDGES.id(), Map.of("gids", gids, "relTypes", types), budget);
    List<GraphEdge> graphEdges =
        edges.rows().stream()
            .map(r -> new GraphEdge((String) r.get("type"), (String) r.get("source"), (String) r.get("target")))
            .toList();
    return new Neighborhood(normalized, graphNodes, graphEdges, nodes.truncated() || edges.truncated());
  }

  private static List<Count> counts(QueryResult result, String key) {
    return result.rows().stream()
        .map(r -> new Count((String) r.get(key), ((Number) r.get("count")).longValue()))
        .toList();
  }

  /** Значения из allowlist; {@code null}, если фильтр не задан. Само значение в ошибку не попадает. */
  private static List<String> allowed(List<String> values, List<String> allowlist, String what) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    var allowedSet = new HashSet<>(allowlist);
    for (String value : values) {
      if (value == null || !allowedSet.contains(value)) {
        throw new InvalidRequestException(
            "Unknown " + what + "; allowed: " + String.join(", ", allowlist));
      }
    }
    return List.copyOf(values);
  }

  private static String normalizeGid(String gid) {
    try {
      return UUID.fromString(gid == null ? "" : gid.strip()).toString();
    } catch (IllegalArgumentException e) {
      // Ввод не эхоим: сообщение исходного исключения содержит значение.
      throw new InvalidRequestException("gid must be a UUID");
    }
  }

  @SuppressWarnings("unchecked")
  private static AssetHit hit(Map<String, Object> row) {
    return new AssetHit(
        (String) row.get("gid"),
        (String) row.get("type"),
        (String) row.get("name"),
        ((Number) row.get("score")).doubleValue(),
        (String) row.get("matchType"),
        (List<String>) row.get("sources"),
        (String) row.get("lastSeenAt"));
  }

  private static GraphNode graphNode(Map<String, Object> row) {
    return new GraphNode(
        (String) row.get("gid"),
        (String) row.get("type"),
        (String) row.get("name"),
        Boolean.TRUE.equals(row.get("isCurrent")),
        ((Number) row.get("distance")).intValue());
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

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> records(QueryResult provenance) {
    if (provenance.rows().isEmpty()) {
      return List.of();
    }
    return (List<Map<String, Object>>) provenance.rows().getFirst().get("records");
  }

  /** Время в ISO-8601 UTC, как {@code lastSeenAt} у поиска; списки обходятся рекурсивно. */
  private static Object jsonValue(Object value) {
    return switch (value) {
      case ZonedDateTime z -> z.toInstant().toString();
      case OffsetDateTime o -> o.toInstant().toString();
      case TemporalAccessor t -> t.toString();
      case List<?> list -> list.stream().map(GraphController::jsonValue).toList();
      case null, default -> value;
    };
  }
}
