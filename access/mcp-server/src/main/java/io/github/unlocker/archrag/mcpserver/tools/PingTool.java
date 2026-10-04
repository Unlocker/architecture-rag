package io.github.unlocker.archrag.mcpserver.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Заглушечный tool для interop-проверки каркаса; предметные tools добавляются в F1–F4. */
@Component
public class PingTool {

  /** Возвращает {@code pong}, а при непустом {@code message} добавляет его эхо. */
  @McpTool(name = "ping", description = "Проверка доступности сервера: возвращает pong и эхо message")
  public String ping(
      @McpToolParam(description = "Необязательное сообщение для эха", required = false) String message) {
    return message == null || message.isBlank() ? "pong" : "pong: " + message;
  }
}
