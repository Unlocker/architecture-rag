package io.github.unlocker.archrag.mcpserver.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Настройки защиты MCP resource.
 *
 * @param resourceUri канонический URI MCP resource: он же audience токена и поле {@code resource}
 *     в Protected Resource Metadata
 * @param toolScopes соответствие «имя tool → требуемый scope»; tool без записи отклоняется
 * @param maxRequestBytes максимальный размер тела {@code POST /mcp}, читаемого для проверки scope
 */
@ConfigurationProperties("archrag.mcp.security")
public record McpSecurityProperties(
    String resourceUri, Map<String, String> toolScopes, DataSize maxRequestBytes) {

  public McpSecurityProperties {
    toolScopes = toolScopes == null ? Map.of() : Map.copyOf(toolScopes);
    maxRequestBytes = maxRequestBytes == null ? DataSize.ofMegabytes(1) : maxRequestBytes;
  }
}
