package io.github.unlocker.archrag.mcpserver.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** Заглушечный tool для interop-проверки каркаса; предметные tools добавляются в F1–F4. */
@Component
public class PingTool {

  /** Возвращает {@code {"status":"ok"}}; аргументов нет. */
  @McpTool(
      name = "ping",
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true,
              openWorldHint = false),
      description =
          "Проверка доступности сервера: возвращает {\"status\":\"ok\"}")
  public String ping() {
    return "{\"status\":\"ok\"}";
  }
}
