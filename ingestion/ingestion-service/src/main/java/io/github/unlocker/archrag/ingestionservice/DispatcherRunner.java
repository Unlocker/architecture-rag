package io.github.unlocker.archrag.ingestionservice;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Запускает {@link JournalDispatcher} в одном потоке с фиксированной паузой между проходами и останавливает его
 * вместе с Boot-контекстом: текущее событие дорабатывает, новые не берутся.
 */
@Component
@ConditionalOnProperty(name = "archrag.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class DispatcherRunner implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(DispatcherRunner.class);

  private final JournalDispatcher dispatcher;
  private final DispatcherProperties props;
  private ScheduledExecutorService executor;

  public DispatcherRunner(JournalDispatcher dispatcher, DispatcherProperties props) {
    this.dispatcher = dispatcher;
    this.props = props;
  }

  @Override
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "journal-dispatcher");
      t.setDaemon(true);
      return t;
    });
    long pause = props.pause().toMillis();
    // Исключение, дошедшее до executor-а, отменило бы все следующие запуски, поэтому проход его ловит сам.
    executor.scheduleWithFixedDelay(this::pass, 0, pause, TimeUnit.MILLISECONDS);
  }

  private void pass() {
    try {
      dispatcher.runOnce();
    } catch (RuntimeException e) {
      LOG.error("dispatcher pass failed: cause={}", e.getClass().getName());
    }
  }

  @Override
  public synchronized void stop() {
    if (executor == null) {
      return;
    }
    dispatcher.stop();
    executor.shutdown();
    try {
      if (!executor.awaitTermination(props.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warn("dispatcher did not stop within {}", props.shutdownTimeout());
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    executor = null;
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
