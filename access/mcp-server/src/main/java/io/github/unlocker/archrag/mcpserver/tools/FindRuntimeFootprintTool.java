package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.templates.FootprintTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code find_runtime_footprint}: где развёрнута система (scope {@code architecture.read}). */
@Component
public class FindRuntimeFootprintTool {

  private final GraphQueries graph;

  FindRuntimeFootprintTool(GraphQueries graph) {
    this.graph = graph;
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
          "Где развёрнута система: deployments сервисов по окружениям и вычислительные узлы"
              + " (VM, физические серверы). Только действующие факты.")
  public FindRuntimeFootprintResult findRuntimeFootprint(
      @McpToolParam(description = "gid узла ITSystem (UUID)") String systemGid,
      @McpToolParam(description = "Код окружения для фильтра, например PROD; без него все", required = false)
          String environment) {
    try {
      UUID.fromString(systemGid == null ? "" : systemGid);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("systemGid must be a UUID");
    }
    String env = environment == null || environment.isBlank() ? null : environment.trim();
    Map<String, Object> params = new HashMap<>();
    params.put("systemGid", systemGid);
    params.put("environment", env);
    QueryResult result = graph.execute(FootprintTemplates.FIND_RUNTIME_FOOTPRINT_ID, params, null);
    if (result.rows().isEmpty()) {
      throw new IllegalArgumentException("system not found");
    }
    Map<String, Object> head = result.rows().get(0);
    List<FindRuntimeFootprintResult.Deployment> deployments = new ArrayList<>();
    for (Map<String, Object> row : result.rows()) {
      if (row.get("gid") != null) {
        deployments.add(deployment(row));
      }
    }
    return new FindRuntimeFootprintResult(
        (String) head.get("systemGid"),
        (String) head.get("systemName"),
        Boolean.TRUE.equals(head.get("systemIsCurrent")),
        deployments,
        result.truncated());
  }

  private static FindRuntimeFootprintResult.Deployment deployment(Map<String, Object> row) {
    List<FindRuntimeFootprintResult.Instance> instances = new ArrayList<>();
    for (Object o : (List<?>) row.get("instances")) {
      instances.add(instance(asMap(o)));
    }
    return new FindRuntimeFootprintResult.Deployment(
        (String) row.get("gid"),
        (String) row.get("name"),
        (String) row.get("version"),
        (String) row.get("serviceGid"),
        (String) row.get("serviceName"),
        (String) row.get("environment"),
        instances);
  }

  private static FindRuntimeFootprintResult.Instance instance(Map<String, Object> m) {
    Map<String, Object> host = m.get("hostedOn") == null ? null : asMap(m.get("hostedOn"));
    return new FindRuntimeFootprintResult.Instance(
        (String) m.get("gid"),
        (String) m.get("type"),
        (String) m.get("hostname"),
        (String) m.get("state"),
        host == null
            ? null
            : new FindRuntimeFootprintResult.HostedOn(
                (String) host.get("gid"),
                (String) host.get("hostname"),
                (String) host.get("serialNumber")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }
}
