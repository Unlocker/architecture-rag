package io.github.unlocker.archrag.demolauncher;

import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.adaptercore.AdapterConfig;
import io.github.unlocker.archrag.adaptercore.RetryPolicy;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * {@code DemoLauncher adapter <EAM|SCM|CMDB|DEPLOY_MAP>} или {@code DemoLauncher stub <...>}. Конфигурация только из
 * env ({@link LauncherConfig}); при неверных аргументах или пустых обязательных переменных процесс завершается с
 * кодом 2 и сообщением без значений секретов.
 *
 * <p>Заглушка: источник на {@code ARCHRAG_STUB_PORT} и управляющий {@code POST /control/upsert} на порту
 * {@value #STUB_CONTROL_PORT} (только в режиме stub).
 *
 * <p>Адаптер: webhook ({@code POST /webhook}, {@code POST /control/snapshot}) на {@code ARCHRAG_WEBHOOK_PORT}, polling и
 * reconciliation. {@code GET /health} на {@code ARCHRAG_HEALTH_PORT} отвечает 200 только после старта всех трёх.
 * Экземпляр адаптера одного источника должен быть ровно один: замок snapshot действует только внутри процесса.
 * Миграции журнала накатывает {@code ingestion-service}, поэтому адаптер стартует после него.
 */
public final class DemoLauncher {

  private static final System.Logger LOG = System.getLogger(DemoLauncher.class.getName());

  /** Порт управляющего входа заглушки ({@link StubControl}); не публикуется и не проксируется. */
  static final int STUB_CONTROL_PORT = 8091;

  private DemoLauncher() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !(args[0].equals("adapter") || args[0].equals("stub"))) {
      System.err.println("usage: DemoLauncher <adapter|stub> <EAM|SCM|CMDB|DEPLOY_MAP>");
      System.exit(2);
    }
    try {
      SourceSystem system = LauncherConfig.parseSystem(args[1]);
      if (args[0].equals("adapter")) {
        runAdapter(LauncherConfig.adapter(system, System.getenv()));
      } else {
        runStub(LauncherConfig.stub(system, System.getenv()));
      }
    } catch (IllegalArgumentException e) {
      System.err.println("configuration error: " + e.getMessage());
      System.exit(2);
    }
  }

  private static void runStub(LauncherConfig.Stub config) throws IOException, InterruptedException {
    var source = StubSources.seeded(Clock.systemUTC()).get(config.system());
    try (var server = new StubSourceServer(source, new InetSocketAddress(config.port()));
        var control = new StubControl(source, new InetSocketAddress(STUB_CONTROL_PORT))) {
      LOG.log(System.Logger.Level.INFO, "stub " + config.system() + " listens on " + server.port()
          + ", control on " + control.port());
      awaitShutdown();
    }
  }

  private static void runAdapter(LauncherConfig.Adapter config) throws IOException, InterruptedException {
    var ds = new PGSimpleDataSource();
    ds.setUrl(config.pgUrl());
    ds.setUser(config.pgUser());
    ds.setPassword(config.pgPassword());
    var journal = new PostgresEventJournal(ds);
    AdapterConfig adapterConfig = new AdapterConfig(config.system(), config.sourceUrl(), config.webhookSecret(),
        AdapterConfig.DEFAULT_REPLAY_WINDOW, 100, config.pollInterval(), config.reconcileInterval(),
        RetryPolicy.defaults());
    try (var store = S3RawPayloadStore.create(config.s3Endpoint(), config.s3AccessKey(), config.s3SecretKey(),
        config.s3Bucket())) {
      store.ensureBucket();
      try (SourceAdapter adapter = create(adapterConfig, journal, store)) {
        var started = new AtomicBoolean();
        HttpServer health = healthServer(config.healthPort(), started);
        try {
          adapter.startWebhook(new InetSocketAddress(config.webhookPort()));
          adapter.startPolling();
          adapter.startReconciliation();
          started.set(true);
          LOG.log(System.Logger.Level.INFO, "adapter " + config.system() + " started");
          awaitShutdown();
        } finally {
          health.stop(0);
        }
      }
    }
  }

  private static SourceAdapter create(AdapterConfig config, PostgresEventJournal journal, S3RawPayloadStore store) {
    return switch (config.system()) {
      case EAM -> EamAdapter.create(config, journal, store);
      case SCM -> ScmAdapter.create(config, journal, store);
      case CMDB -> AssetAdapter.create(config, journal, store);
      case DEPLOY_MAP -> DeploymapAdapter.create(config, journal, store);
    };
  }

  private static HttpServer healthServer(int port, AtomicBoolean started) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", ex -> {
      try (ex) {
        boolean up = started.get() && "GET".equals(ex.getRequestMethod());
        byte[] body = (up ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}").getBytes();
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(up ? 200 : 503, body.length);
        ex.getResponseBody().write(body);
      }
    });
    server.start();
    return server;
  }

  /** Блокирует главный поток до SIGTERM/SIGINT; дальше JVM завершается сама, ресурсы закрывает ОС. */
  private static void awaitShutdown() throws InterruptedException {
    var stop = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
    stop.await();
  }
}
