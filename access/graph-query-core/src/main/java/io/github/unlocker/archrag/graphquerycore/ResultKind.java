package io.github.unlocker.archrag.graphquerycore;

/** Что возвращает шаблон; определяет, какой потолок строк действует. */
public enum ResultKind {
  /** Строки-узлы (проекции): потолок {@code maxNodes}. */
  NODES,
  /** Строки-пути: потолок {@code maxPaths}. */
  PATHS;

  /** Максимум строк для бюджета. */
  public int maxRows(ResultBudget budget) {
    return this == NODES ? budget.maxNodes() : budget.maxPaths();
  }
}
