package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.templates.RuntimeTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import io.github.unlocker.archrag.mcpserver.graph.StalenessProperties;
import io.github.unlocker.archrag.mcpserver.tools.FindRuntimeFootprintResult.*;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool {@code find_runtime_footprint}: где развёрнута система (scope {@code architecture.read}).
 *
 * <p>Строк на (service, deployment, compute): на большой системе произведение упрётся в
 * {@code max-nodes}, тогда {@code truncated=true}; для PoC это приемлемо.
 */
@Component
public class FindRuntimeFootprintTool {

  private final GraphQueries graph;
  private final StalenessProperties staleness;
  private final Clock clock;

  FindRuntimeFootprintTool(GraphQueries graph, StalenessProperties staleness, Clock clock) {
    this.graph = graph;
    this.staleness = staleness;
    this.clock = clock;
  }

  /**
   * Возвращает действующие развёртывания системы, при необходимости в одном окружении.
   *
   * @throws IllegalArgumentException {@code systemGid} не UUID либо система не найдена; значение из
   *     ввода в текст ошибки не попадает
   */
  @McpTool(
      name = "find_runtime_footprint",
      description =
          "Где развёрнута система: сервисы, их deployments по окружениям и вычислительные узлы"
              + " (VM, физические серверы) с provenance и признаком stale. Только действующие факты.")
  public FindRuntimeFootprintResult findRuntimeFootprint(
      @McpToolParam(description = "gid узла ITSystem (UUID)") String systemGid,
      @McpToolParam(description = "Код окружения для фильтра, например PROD; без него все", required = false)
          String environment) {
    try {
      UUID.fromString(systemGid == null ? "" : systemGid);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("systemGid must be a UUID");
    }
    String env = environment == null || environment.isBlank() ? null : environment.strip();
    Map<String, Object> params = new HashMap<>();
    params.put("systemGid", systemGid);
    params.put("environment", env);
    // budget = null: исполнитель берёт потолки QueryLimits (limits.*) целиком, как get_asset с ResultBudget.
    QueryResult result = graph.execute(RuntimeTemplates.RUNTIME_FOOTPRINT_ID, params, null);
    if (result.rows().isEmpty()) {
      throw new IllegalArgumentException("asset not found");
    }
    return assemble(result, env);
  }

  private FindRuntimeFootprintResult assemble(QueryResult result, String env) {
    Instant threshold = clock.instant().minus(staleness.staleAfter());
    Map<String, String> warnings = new LinkedHashMap<>();
    Map<String, Object> system = map(result.rows().get(0).get("system"));
    String systemSeen = seen(system);
    boolean systemStale = stale(systemSeen, threshold);
    warn(warnings, systemStale, "ITSystem", system, systemSeen);

    Map<String, ServiceBuilder> services = new LinkedHashMap<>();
    for (Map<String, Object> row : result.rows()) {
      Map<String, Object> svc = map(row.get("service"));
      if (svc == null) {
        continue;
      }
      ServiceBuilder sb =
          services.computeIfAbsent(
              (String) svc.get("gid"),
              g -> {
                String s = seen(svc);
                boolean st = stale(s, threshold);
                warn(warnings, st, "Service", svc, s);
                return new ServiceBuilder(svc, s, st);
              });
      Map<String, Object> d = map(row.get("deployment"));
      if (d == null) {
        continue;
      }
      DeploymentBuilder db =
          sb.deployments.computeIfAbsent(
              (String) d.get("gid"),
              g -> {
                String s = seen(d);
                boolean st = stale(s, threshold);
                warn(warnings, st, "Deployment", d, s);
                return new DeploymentBuilder(d, s, st);
              });
      Map<String, Object> c = map(row.get("compute"));
      if (c == null || db.compute.containsKey((String) c.get("gid"))) {
        continue;
      }
      String cs = seen(c);
      boolean cst = stale(cs, threshold);
      warn(warnings, cst, (String) c.get("type"), c, cs);
      HostFootprint host = null;
      Map<String, Object> p = map(row.get("host"));
      if (p != null) {
        String ps = seen(p);
        boolean pst = stale(ps, threshold);
        warn(warnings, pst, "PhysicalServer", p, ps);
        host =
            new HostFootprint(
                (String) p.get("gid"), (String) p.get("hostname"), (String) p.get("state"), ps, pst,
                sources(p), edge(p.get("hostedOn")));
      }
      db.compute.put(
          (String) c.get("gid"),
          new ComputeFootprint(
              (String) c.get("gid"), (String) c.get("type"), (String) c.get("hostname"),
              (String) c.get("state"), cs, cst, sources(c), edge(c.get("runsOn")), host));
    }
    return new FindRuntimeFootprintResult(
        (String) system.get("gid"),
        (String) system.get("name"),
        systemSeen,
        systemStale,
        sources(system),
        env,
        services.values().stream().map(ServiceBuilder::build).toList(),
        staleness.staleAfter().toString(),
        new ArrayList<>(warnings.values()),
        result.truncated());
  }

  private static final class ServiceBuilder {
    final Map<String, Object> node;
    final String seen;
    final boolean stale;
    final Map<String, DeploymentBuilder> deployments = new LinkedHashMap<>();

    ServiceBuilder(Map<String, Object> node, String seen, boolean stale) {
      this.node = node;
      this.seen = seen;
      this.stale = stale;
    }

    ServiceFootprint build() {
      return new ServiceFootprint(
          (String) node.get("gid"), (String) node.get("name"), seen, stale, sources(node),
          deployments.values().stream().map(DeploymentBuilder::build).toList());
    }
  }

  private static final class DeploymentBuilder {
    final Map<String, Object> node;
    final String seen;
    final boolean stale;
    final Map<String, ComputeFootprint> compute = new LinkedHashMap<>();

    DeploymentBuilder(Map<String, Object> node, String seen, boolean stale) {
      this.node = node;
      this.seen = seen;
      this.stale = stale;
    }

    DeploymentFootprint build() {
      return new DeploymentFootprint(
          (String) node.get("gid"), (String) node.get("name"), (String) node.get("version"),
          (String) node.get("status"), (String) node.get("environment"), seen, stale,
          sources(node), edge(node.get("hasDeployment")), List.copyOf(compute.values()));
    }
  }

  /** lastSeenAt из строки Cypher в UTC ISO-8601; {@code null}, если не задан. */
  private static String seen(Map<String, Object> node) {
    Object v = node.get("lastSeenAt");
    return v == null ? null : OffsetDateTime.parse((String) v).toInstant().toString();
  }

  private static boolean stale(String lastSeenAt, Instant threshold) {
    return lastSeenAt == null || Instant.parse(lastSeenAt).isBefore(threshold);
  }

  private static void warn(
      Map<String, String> warnings, boolean stale, String type, Map<String, Object> node, String seen) {
    if (stale) {
      warnings.putIfAbsent(
          (String) node.get("gid"), "stale: " + type + " " + node.get("gid") + " lastSeenAt=" + seen);
    }
  }

  private static List<FactSource> sources(Map<String, Object> node) {
    List<FactSource> out = new ArrayList<>();
    for (Object o : (List<?>) node.get("sources")) {
      Map<String, Object> m = map(o);
      out.add(
          new FactSource(
              (String) m.get("source"), (String) m.get("sourceType"), (String) m.get("sourceId"),
              (String) m.get("authority")));
    }
    return out;
  }

  private static EdgeProvenance edge(Object o) {
    Map<String, Object> m = map(o);
    return m == null
        ? null
        : new EdgeProvenance(
            (String) m.get("source"), (String) m.get("sourceType"), (String) m.get("sourceId"),
            (String) m.get("validFrom"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }
}
