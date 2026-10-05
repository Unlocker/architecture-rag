package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.DependencyTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import io.github.unlocker.archrag.mcpserver.graph.StalenessProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code trace_dependencies}: обход графа вверх или вниз по разрешённым типам связей, режимы trace и impact. */
@Component
public class TraceDependenciesTool {

  private static final String DOWNSTREAM = "downstream";
  private static final String UPSTREAM = "upstream";
  private static final String MODE_TRACE = "trace";
  private static final String MODE_IMPACT = "impact";
  private static final int MAX_ENVIRONMENT_LENGTH = 128;

  private static final Comparator<Map<String, Object>> PATH_ORDER =
      Comparator.<Map<String, Object>>comparingInt(row -> pathNodes(row).size())
          .thenComparing(
              (a, b) -> {
                var x = pathNodes(a);
                var y = pathNodes(b);
                for (int i = 0; i < x.size(); i++) {
                  int c = String.valueOf(x.get(i).get("gid")).compareTo(String.valueOf(y.get(i).get("gid")));
                  if (c != 0) {
                    return c;
                  }
                }
                return 0;
              });

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> pathNodes(Map<String, Object> row) {
    return (List<Map<String, Object>>) row.get("nodes");
  }

  private final GraphQueries queries;
  private final QueryLimits limits;
  private final StalenessProperties staleness;
  private final Clock clock;

  TraceDependenciesTool(GraphQueries queries, QueryLimits limits, StalenessProperties staleness, Clock clock) {
    this.queries = queries;
    this.limits = limits;
    this.staleness = staleness;
    this.clock = clock;
  }

  /**
   * Трассирует зависимости актива: {@code trace} (по умолчанию) или {@code impact}.
   *
   * <p>{@code impact} идёт против стрелок связей из {@link DependencyTemplates#IMPACT_RELATION_TYPES} до
   * {@code Service}/{@code ITSystem}; {@code environment} отсекает пути через {@code Deployment} чужого окружения.
   * Пути не сортируются в Cypher (иначе Neo4j не остановится на лимите), порядок задаётся здесь; при
   * {@code truncated=true} подмножество путей может быть недетерминированным.
   *
   * @throws IllegalArgumentException {@code gid} не UUID или актив не найден, неизвестное направление
   *     (в {@code impact} допустим только {@code upstream}), {@code maxDepth} вне 1..лимит, тип связи вне
   *     allowlist режима, {@code environment} вне {@code impact} или слишком длинный, неизвестный режим;
   *     значения ввода в текст ошибки не попадают
   */
  @McpTool(
      name = "trace_dependencies",
      description =
          "Трассировка зависимостей актива по gid. mode=trace (по умолчанию): upstream идёт против направления связей,"
              + " downstream по направлению; связи из allowlist (DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON,"
              + " HOSTED_ON, OWNED_BY). mode=impact: только вверх (против стрелок) по RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT,"
              + " DECOMPOSED_INTO, DEPENDS_ON до затронутых Service/ITSystem (affected); необязательный environment"
              + " (Environment.code) требует, чтобы каждый Deployment на пути был в этом окружении, путь без Deployment"
              + " фильтр проходит. Только действующие связи и текущие узлы. Шаг пути объясним: связь несёт источник"
              + " (assertedBy*), sourceFetchedAt, stale; узел несёт stale и conflicts. truncated=true, если путей больше"
              + " лимита: тогда состав путей может отличаться между вызовами. Ограничение: связь, утверждённая записью без узла,"
              + " пока не несёт sourceFetchedAt и всегда stale.")
  public TraceDependenciesResult traceDependencies(
      @McpToolParam(description = "gid стартового актива (UUID)") String gid,
      @McpToolParam(description = "upstream или downstream, по умолчанию downstream; для impact только upstream", required = false)
          String direction,
      @McpToolParam(description = "Глубина обхода, от 1 до лимита сервера (по умолчанию лимит)", required = false)
          Integer maxDepth,
      @McpToolParam(description = "Типы связей из allowlist режима, по умолчанию все", required = false)
          List<String> relationTypes,
      @McpToolParam(description = "Режим: trace (по умолчанию) или impact", required = false) String mode,
      @McpToolParam(description = "Только для impact: Environment.code", required = false) String environment) {
    String normalizedGid = normalizeGid(gid);
    String normalizedMode = mode == null || mode.isBlank() ? MODE_TRACE : mode.strip().toLowerCase(Locale.ROOT);
    boolean impact = MODE_IMPACT.equals(normalizedMode);
    if (!impact && !MODE_TRACE.equals(normalizedMode)) {
      throw new IllegalArgumentException("mode must be trace or impact");
    }
    String dir;
    if (impact) {
      if (direction != null && !direction.isBlank() && !UPSTREAM.equals(direction.strip().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("direction must be upstream for mode impact");
      }
      dir = UPSTREAM;
    } else {
      dir = direction == null || direction.isBlank() ? DOWNSTREAM : direction.strip().toLowerCase(Locale.ROOT);
      if (!dir.equals(DOWNSTREAM) && !dir.equals(UPSTREAM)) {
        throw new IllegalArgumentException("direction must be upstream or downstream");
      }
    }
    String env = environment == null || environment.isBlank() ? null : environment.strip();
    if (env != null && (!impact || env.length() > MAX_ENVIRONMENT_LENGTH)) {
      throw new IllegalArgumentException(
          impact ? "environment is too long" : "environment is supported only for mode impact");
    }
    int depth = maxDepth == null ? limits.maxDepth() : maxDepth;
    if (depth < 1 || depth > limits.maxDepth()) {
      throw new IllegalArgumentException("maxDepth must be in 1.." + limits.maxDepth());
    }
    List<String> types = validRelationTypes(relationTypes, impact ? DependencyTemplates.IMPACT_RELATION_TYPES : DependencyTemplates.TRACE_RELATION_TYPES);

    var template =
        impact
            ? DependencyTemplates.IMPACT_UPSTREAM
            : dir.equals(DOWNSTREAM) ? DependencyTemplates.TRACE_DOWNSTREAM : DependencyTemplates.TRACE_UPSTREAM;
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("gid", normalizedGid);
    params.put("relationTypes", types);
    if (impact) {
      params.put("environment", env);
    }
    ResultBudget budget =
        new ResultBudget(depth, limits.maxNodes(), limits.maxPaths(), limits.timeout(), limits.maxResponseBytes());
    QueryResult result = queries.execute(template.id(), params, budget);

    if (result.rows().isEmpty() && !exists(normalizedGid, budget)) {
      throw new IllegalArgumentException("asset not found");
    }
    return assemble(normalizedGid, normalizedMode, dir, env, result);
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

  private static List<String> validRelationTypes(List<String> requested, List<String> allowlist) {
    if (requested == null || requested.isEmpty()) {
      return allowlist;
    }
    var allowed = Set.copyOf(allowlist);
    for (String type : requested) {
      if (type == null || !allowed.contains(type)) {
        throw new IllegalArgumentException("Unknown relation type; allowed: " + String.join(", ", allowlist));
      }
    }
    return List.copyOf(requested);
  }

  /** Старше порога: ровно на пороге ещё свежо; не заданное время считается устаревшим. */
  private boolean isStale(String timestamp, Instant threshold) {
    return timestamp == null || OffsetDateTime.parse(timestamp).toInstant().isBefore(threshold);
  }

  @SuppressWarnings("unchecked")
  private TraceDependenciesResult assemble(String gid, String mode, String direction, String environment, QueryResult result) {
    Duration staleAfter = staleness.staleAfter();
    Instant threshold = clock.instant().minus(staleAfter);
    Map<String, TraceDependenciesResult.NodeRef> nodes = new LinkedHashMap<>();
    Map<TraceDependenciesResult.RelationKey, TraceDependenciesResult.RelationRef> relations = new LinkedHashMap<>();
    List<TraceDependenciesResult.TracePath> paths = new ArrayList<>();
    Set<String> affected = new LinkedHashSet<>();
    List<Map<String, Object>> rows = new ArrayList<>(result.rows());
    rows.sort(PATH_ORDER);
    for (Map<String, Object> row : rows) {
      List<String> pathNodeGids = new ArrayList<>();
      for (Map<String, Object> n : pathNodes(row)) {
        String nodeGid = (String) n.get("gid");
        pathNodeGids.add(nodeGid);
        nodes.computeIfAbsent(
            nodeGid,
            k -> {
              String lastSeenAt = (String) n.get("lastSeenAt");
              return new TraceDependenciesResult.NodeRef(
                  nodeGid, (String) n.get("label"), (String) n.get("name"), lastSeenAt, isStale(lastSeenAt, threshold),
                  conflicts((List<Map<String, Object>>) n.get("conflicts")));
            });
      }
      List<TraceDependenciesResult.RelationKey> pathRelations = new ArrayList<>();
      for (Map<String, Object> r : (List<Map<String, Object>>) row.get("relations")) {
        var key = new TraceDependenciesResult.RelationKey((String) r.get("type"), (String) r.get("from"), (String) r.get("to"));
        pathRelations.add(key);
        relations.computeIfAbsent(
            key,
            k -> {
              String fetchedAt = (String) r.get("sourceFetchedAt");
              Boolean active = (Boolean) r.get("sourceActive");
              return new TraceDependenciesResult.RelationRef(
                  key.type(), key.from(), key.to(), (String) r.get("validFrom"),
                  (String) r.get("assertedBySource"), (String) r.get("assertedByType"), (String) r.get("assertedById"),
                  fetchedAt, active, !Boolean.TRUE.equals(active) || isStale(fetchedAt, threshold));
            });
      }
      paths.add(new TraceDependenciesResult.TracePath(pathNodeGids, pathRelations));
      if (MODE_IMPACT.equals(mode)) {
        affected.add(pathNodeGids.getLast());
      }
    }
    return new TraceDependenciesResult(
        gid, mode, direction, environment, staleAfter.toString(), List.copyOf(nodes.values()),
        List.copyOf(relations.values()), paths, List.copyOf(affected), result.truncated());
  }

  private static List<TraceDependenciesResult.Conflict> conflicts(List<Map<String, Object>> raw) {
    if (raw == null) {
      return List.of();
    }
    return raw.stream()
        .map(c -> new TraceDependenciesResult.Conflict(
            (String) c.get("source"), (String) c.get("sourceType"), (String) c.get("sourceId"), stringList(c.get("properties"))))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object value) {
    return value == null ? List.of() : List.copyOf((List<String>) value);
  }
}
