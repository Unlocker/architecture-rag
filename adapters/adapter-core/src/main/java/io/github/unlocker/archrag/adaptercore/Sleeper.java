package io.github.unlocker.archrag.adaptercore;

import java.time.Duration;

/** Пауза между попытками; в тестах подменяется, чтобы не зависеть от реального времени. */
@FunctionalInterface
public interface Sleeper {

  /** Реальная пауза потока. */
  Sleeper THREAD = d -> Thread.sleep(d.toMillis());

  void sleep(Duration duration) throws InterruptedException;
}
