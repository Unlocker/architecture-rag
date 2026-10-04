package io.github.unlocker.archrag.ingestionservice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки диспетчера журнала.
 *
 * @param enabled выключатель (в тестах, которые сами управляют обработкой)
 * @param pause пауза между проходами
 * @param batchSize размер страницы выборки, {@code 1..1000}
 * @param shutdownTimeout сколько ждать завершения текущего события при остановке
 */
@ConfigurationProperties("archrag.dispatcher")
public record DispatcherProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1s") Duration pause,
    @DefaultValue("100") int batchSize,
    @DefaultValue("30s") Duration shutdownTimeout) {

  public DispatcherProperties {
    if (pause.isNegative() || pause.isZero()) {
      throw new IllegalArgumentException("archrag.dispatcher.pause must be positive");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("archrag.dispatcher.batch-size must be in 1..1000");
    }
  }
}
