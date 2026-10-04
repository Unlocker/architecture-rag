package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Собранный адаптер одного источника: коннектор, приём webhook и цикл polling. Конкретные адаптеры
 * ({@code eam}, {@code scm}, ...) только задают источник и конфигурацию. Адаптер не получает
 * Neo4j-креденшелов и ничего не пишет в источник.
 */
public final class SourceAdapter implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(SourceAdapter.class.getName());

  private final AdapterConfig config;
  private final HttpSourceConnector connector;
  private final WebhookHandler webhook;
  private final Poller poller;
  private WebhookServer server;
  private ScheduledExecutorService scheduler;

  public SourceAdapter(AdapterConfig config, EventJournal journal, RawPayloadStore rawStore) {
    this.config = config;
    this.connector = new HttpSourceConnector(config.system(), config.sourceBaseUri());
    this.webhook = new WebhookHandler(config, connector, journal, rawStore, Clock.systemUTC());
    this.poller = Poller.create(config, connector, journal, rawStore);
  }

  public WebhookHandler webhook() {
    return webhook;
  }

  public Poller poller() {
    return poller;
  }

  public SourceConnector connector() {
    return connector;
  }

  /** Запускает webhook endpoint на {@code address}. */
  public synchronized WebhookServer startWebhook(InetSocketAddress address) {
    if (server == null) {
      server = new WebhookServer(webhook, address);
    }
    return server;
  }

  /**
   * Запускает периодический polling. Сбой цикла логируется (тип исключения) и не останавливает
   * расписание: следующий цикл продолжит с сохранённого курсора.
   */
  public synchronized void startPolling() {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            PollResult r = poller.pollOnce();
            LOG.log(System.Logger.Level.DEBUG, config.system().code() + " poll " + r.outcome());
          } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                config.system().code() + " poll failed: " + e.getClass().getName());
          }
        },
        0,
        config.pollInterval().toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    if (server != null) {
      server.close();
    }
    connector.close();
  }
}
