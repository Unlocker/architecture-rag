package io.github.unlocker.archrag.graphquerycore;

import java.time.Duration;

/**
 * Потолки бюджета из конфигурации: запрос не может получить больше.
 *
 * @param maxDepth потолок глубины обхода
 * @param maxNodes потолок числа строк
 * @param maxPaths потолок числа путей
 * @param timeout потолок таймаута
 * @param maxResponseBytes потолок размера ответа
 */
public record QueryLimits(
    int maxDepth, int maxNodes, int maxPaths, Duration timeout, long maxResponseBytes) {

  public QueryLimits {
    // Те же инварианты, что у бюджета: потолок — это бюджет, который нельзя превысить.
    new ResultBudget(maxDepth, maxNodes, maxPaths, timeout, maxResponseBytes);
  }

  /** Потолки как бюджет: используется, когда шаблон и вызывающий не задали своего. */
  public ResultBudget asBudget() {
    return new ResultBudget(maxDepth, maxNodes, maxPaths, timeout, maxResponseBytes);
  }
}
