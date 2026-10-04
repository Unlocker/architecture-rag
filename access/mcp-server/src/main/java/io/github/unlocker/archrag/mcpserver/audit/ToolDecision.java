package io.github.unlocker.archrag.mcpserver.audit;

/** Исход вызова tool; значение идёт в аудит-запись и в low-cardinality тег {@code decision}. */
public enum ToolDecision {
  /** Вызов выполнен успешно. */
  ALLOWED,
  /** У токена нет scope, требуемого для tool. */
  DENIED_SCOPE,
  /** Tool без записи в {@code tool-scopes} (fail-closed). */
  DENIED_UNKNOWN_TOOL,
  /** Запрос к графу превысил таймаут. */
  TIMEOUT,
  /** Любая другая ошибка выполнения tool. */
  ERROR
}
