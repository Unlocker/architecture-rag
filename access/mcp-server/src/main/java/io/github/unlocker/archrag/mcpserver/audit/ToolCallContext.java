package io.github.unlocker.archrag.mcpserver.audit;

import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Накопитель сведений о запросах к графу в рамках одного вызова tool.
 *
 * <p>Открывается и закрывается {@link ToolCallAuditAspect} через {@link ThreadLocal}. Ограничение:
 * tool, который выполняет запросы в другом потоке, не попадёт в аудит ({@code templates}/{@code
 * rows} останутся пустыми); в PoC tools синхронны. Вне вызова tool {@link #record} ничего не делает.
 */
public final class ToolCallContext {

  private static final ThreadLocal<ToolCallContext> CURRENT = new ThreadLocal<>();

  private final List<String> templates = new ArrayList<>();
  private long rows;
  private boolean truncated;

  private ToolCallContext() {}

  static ToolCallContext open() {
    var context = new ToolCallContext();
    CURRENT.set(context);
    return context;
  }

  static void close() {
    CURRENT.remove();
  }

  /** Учитывает результат запроса в текущем вызове tool; без открытого вызова — no-op. */
  public static void record(QueryResult result) {
    ToolCallContext context = CURRENT.get();
    if (context != null) {
      context.templates.add(result.templateId());
      context.rows += result.rowCount();
      context.truncated |= result.truncated();
    }
  }

  List<String> templates() {
    return List.copyOf(templates);
  }

  long rows() {
    return rows;
  }

  boolean truncated() {
    return truncated;
  }
}
