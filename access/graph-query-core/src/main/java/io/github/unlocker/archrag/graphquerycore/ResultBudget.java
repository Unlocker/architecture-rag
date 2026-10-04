package io.github.unlocker.archrag.graphquerycore;

import java.time.Duration;

/**
 * Бюджет одного запроса к графу.
 *
 * <p>Инвариант: все значения положительные. Запрошенный бюджет перед исполнением режется до
 * потолков {@link QueryLimits} методом {@link #clampTo(QueryLimits)}.
 *
 * @param maxDepth максимальная глубина обхода (подставляется в {@code {maxDepth}} шаблона)
 * @param maxNodes максимальное число возвращаемых строк
 * @param maxPaths максимальное число путей; резервируется для шаблонов обхода
 * @param timeout таймаут транзакции
 * @param maxResponseBytes порог размера сериализованного ответа
 */
public record ResultBudget(
    int maxDepth, int maxNodes, int maxPaths, Duration timeout, long maxResponseBytes) {

  public ResultBudget {
    if (maxDepth < 1 || maxNodes < 1 || maxPaths < 1 || maxResponseBytes < 1) {
      throw new IllegalArgumentException("Budget values must be positive: " + this);
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Budget timeout must be positive");
    }
  }

  /** Возвращает бюджет, каждое поле которого не больше соответствующего потолка. */
  public ResultBudget clampTo(QueryLimits limits) {
    return new ResultBudget(
        Math.min(maxDepth, limits.maxDepth()),
        Math.min(maxNodes, limits.maxNodes()),
        Math.min(maxPaths, limits.maxPaths()),
        timeout.compareTo(limits.timeout()) <= 0 ? timeout : limits.timeout(),
        Math.min(maxResponseBytes, limits.maxResponseBytes()));
  }
}
