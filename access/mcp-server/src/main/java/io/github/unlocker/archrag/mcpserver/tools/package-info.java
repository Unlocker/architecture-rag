/**
 * MCP tools. Правило аудита: в граф tool ходит только через {@code graph.GraphQueries}, а не через
 * {@code GraphQueryExecutor} напрямую; иначе ID шаблона и число строк не попадут в аудит-запись.
 * Каждый tool обязан иметь запись в {@code archrag.mcp.security.tool-scopes}.
 */
package io.github.unlocker.archrag.mcpserver.tools;
