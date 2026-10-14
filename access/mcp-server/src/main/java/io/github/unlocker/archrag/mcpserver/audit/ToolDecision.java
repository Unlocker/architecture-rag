package io.github.unlocker.archrag.mcpserver.audit;

/** Исход вызова tool; значение идёт в аудит-запись и в low-cardinality тег {@code decision}. */
public enum ToolDecision {
  /** Вызов выполнен успешно. */
  ALLOWED,
  /** У токена нет scope, требуемого для tool. */
  DENIED_SCOPE,
  /** Tool без записи в {@code tool-scopes} (fail-closed). */
  DENIED_UNKNOWN_TOOL,
  /** Тело {@code POST /mcp} отклонено фильтром с 400: кодировка, невалидный JSON или не объект. */
  REJECTED_INVALID_REQUEST,
  /** Тело {@code POST /mcp} больше {@code max-request-bytes}: отклонено фильтром с 413. */
  REJECTED_TOO_LARGE,
  /** Запрос к графу превысил таймаут. */
  TIMEOUT,
  /** Любая другая ошибка выполнения tool. */
  ERROR
}
