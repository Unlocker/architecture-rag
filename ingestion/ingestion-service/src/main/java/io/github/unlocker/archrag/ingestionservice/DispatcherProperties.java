package io.github.unlocker.archrag.ingestionservice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки диспетчера журнала.
 *
 * @param enabled выключатель (в тестах, которые сами управляют обработкой)
 * @param pollInterval пауза между проходами
 * @param batchSize размер страницы выборки, {@code 1..1000}
 * @param retryDelay сколько {@code RETRYING} (и прерванное событие) ждёт после последнего изменения
 * @param shutdownTimeout сколько ждать завершения текущего события при остановке
 */
@ConfigurationProperties("archrag.dispatcher")
public record DispatcherProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("100") int batchSize,
    @DefaultValue("30s") Duration retryDelay,
    @DefaultValue("30s") Duration shutdownTimeout) {

  public DispatcherProperties {
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("archrag.dispatcher.poll-interval must be positive");
    }
    if (retryDelay.isNegative()) {
      throw new IllegalArgumentException("archrag.dispatcher.retry-delay must not be negative");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("archrag.dispatcher.batch-size must be in 1..1000");
    }
  }
}
