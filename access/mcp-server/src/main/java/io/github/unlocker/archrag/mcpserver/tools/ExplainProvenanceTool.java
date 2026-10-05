package io.github.unlocker.archrag.mcpserver.tools;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tool {@code explain_provenance}: источники актива, авторитетность и открытые конфликты. */
@Component
public class ExplainProvenanceTool {

  private static final Pattern PROPERTY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

  private final GraphQueries queries;
  private final QueryLimits limits;
  private final AuthorityMatrix matrix = AuthorityMatrix.defaults();

  ExplainProvenanceTool(GraphQueries queries, QueryLimits limits) {
    this.queries = queries;
    this.limits = limits;
  }

  /**
   * Возвращает записи источников актива, авторитетных мастеров и состояние конфликта.
   *
   * @throws IllegalArgumentException {@code gid} не UUID, {@code property} не проходит allowlist или
   *     актив не найден; введённые значения в текст ошибки не попадают
   */
  @McpTool(
      name = "explain_provenance",
      description =
          "Происхождение актива по gid (опционально для свойства property): записи источников с"
              + " версией, fetchedAt, contentHash, confidence/authority ребра ASSERTS, признак"
              + " авторитетного источника по authority matrix, active/deletedAt и открытые конфликты"
              + " (имена свойств, по которым запись расходится с мастером). Значения свойств не"
              + " возвращаются: расхождение показано парой записей мастера и диссидента.")
  public ExplainProvenanceResult explainProvenance(
      @McpToolParam(description = "gid актива (UUID)") String gid,
      @McpToolParam(description = "Имя свойства, например criticality", required = false)
          String property) {
    String normalizedGid = normalizeGid(gid);
    String normalizedProperty = normalizeProperty(property);
    ResultBudget budget =
        new ResultBudget(
            limits.maxDepth(),
            limits.maxNodes(),
            limits.maxPaths(),
            limits.timeout(),
            limits.maxResponseBytes());

    QueryResult card =
        queries.execute(AssetTemplates.EXPLAIN_PROVENANCE.id(), Map.of("gid", normalizedGid), budget);
    if (card.rows().isEmpty()) {
      throw new IllegalArgumentException("asset not found");
    }
    Map<String, Object> row = card.rows().getFirst();
    String type = (String) row.get("type");
    Set<SourceSystemCode> masters = authorities(type, normalizedProperty);
    List<ExplainProvenanceResult.RecordRef> records = records(row, masters, normalizedProperty);
    boolean open = records.stream().anyMatch(r -> !r.conflictProperties().isEmpty());
    return new ExplainProvenanceResult(
        (String) row.get("gid"),
        type,
        normalizedProperty,
        Boolean.TRUE.equals(row.get("isCurrent")),
        (String) row.get("deletedAt"),
        masters.stream().map(Enum::name).sorted().toList(),
        open ? "OPEN" : "NONE",
        records);
  }

  private static String normalizeGid(String gid) {
    try {
      return UUID.fromString(gid == null ? "" : gid.strip()).toString();
    } catch (IllegalArgumentException e) {
      // Ввод не эхоим: сообщение исходного исключения содержит значение.
      throw new IllegalArgumentException("gid must be a UUID");
    }
  }

  private static String normalizeProperty(String property) {
    if (property == null || property.isBlank()) {
      return null;
    }
    String stripped = property.strip();
    if (!PROPERTY.matcher(stripped).matches()) {
      throw new IllegalArgumentException("property is invalid");
    }
    return stripped;
  }

  /** Мастера узла или свойства; неизвестная метка даёт пустой набор (fail closed, как в матрице). */
  private Set<SourceSystemCode> authorities(String type, String property) {
    for (NodeLabel label : NodeLabel.values()) {
      if (label.label().equals(type)) {
        return property == null
            ? matrix.nodeAuthorities(label)
            : matrix.propertyAuthorities(label, property);
      }
    }
    return Set.of();
  }

  @SuppressWarnings("unchecked")
  private static List<ExplainProvenanceResult.RecordRef> records(
      Map<String, Object> row, Set<SourceSystemCode> masters, String property) {
    return ((List<Map<String, Object>>) row.get("records"))
        .stream().map(r -> record(r, masters, property)).toList();
  }

  @SuppressWarnings("unchecked")
  private static ExplainProvenanceResult.RecordRef record(
      Map<String, Object> r, Set<SourceSystemCode> masters, String property) {
    String source = (String) r.get("source");
    List<String> conflicts = r.get("conflicts") == null ? List.of() : (List<String>) r.get("conflicts");
    if (property != null) {
      conflicts = conflicts.contains(property) ? List.of(property) : List.of();
    }
    return new ExplainProvenanceResult.RecordRef(
        source,
        (String) r.get("sourceType"),
        (String) r.get("sourceId"),
        (String) r.get("sourceVersion"),
        (String) r.get("fetchedAt"),
        (String) r.get("contentHash"),
        r.get("confidence") instanceof Number n ? n.doubleValue() : null,
        (String) r.get("authority"),
        masters.stream().anyMatch(m -> m.name().equals(source)),
        Boolean.TRUE.equals(r.get("active")),
        (String) r.get("deletedAt"),
        conflicts);
  }
}
