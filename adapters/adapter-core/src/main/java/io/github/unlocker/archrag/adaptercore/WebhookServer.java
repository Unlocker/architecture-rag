package io.github.unlocker.archrag.adaptercore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP-привязка {@link WebhookHandler} на JDK {@code HttpServer}: {@code POST /webhook}. Тело
 * ограничено {@link #MAX_BODY_BYTES}. Ответ всегда с пустым телом, чтобы не раскрывать детали
 * проверки подписи.
 *
 * <p>Если передан запуск snapshot, добавляется {@code POST /control/snapshot} (ручной reconcile, E1.7): запускает
 * {@link Poller#snapshotOnce()} и отвечает JSON {@code {"outcome","appended","syncRunId"}}: {@code 202} — цикл
 * завершён, {@code 409} — {@code ALREADY_RUNNING}, {@code 503} — источник недоступен ({@code GAVE_UP}). Контекст не
 * открыт только держателю bearer-токена администратора ({@link ControlTokenValidator}): нет токена — {@code 401},
 * нет роли — {@code 403}. Без валидатора контекст не создаётся (fail closed), а в лог пишется предупреждение. Webhook
 * остаётся на HMAC-подписи. Запрос выполняется синхронно, поэтому клиент должен ждать завершения snapshot.
 *
 * <p>Необработанное исключение (сбой журнала/S3) логируется только типом, без сообщения, и даёт
 * {@code 500}: источник повторит доставку.
 */
public final class WebhookServer implements AutoCloseable {

  /** Максимальный размер тела webhook. */
  public static final int MAX_BODY_BYTES = 64 * 1024;

  private static final System.Logger LOG = System.getLogger(WebhookServer.class.getName());

  private final HttpServer server;
  private final WebhookHandler handler;

  /** @param port порт; 0 — свободный */
  public WebhookServer(WebhookHandler handler, InetSocketAddress address) {
    this(handler, address, null, null);
  }

  /**
   * @param snapshot запуск полного snapshot для {@code /control/snapshot}; {@code null} — контекст не создаётся
   * @param controlAuth проверка токена администратора для {@code /control/snapshot}; {@code null} — контекст не
   *     создаётся
   */
  public WebhookServer(
      WebhookHandler handler,
      InetSocketAddress address,
      Supplier<PollResult> snapshot,
      ControlTokenValidator controlAuth) {
    this.handler = handler;
    try {
      this.server = HttpServer.create(address, 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start webhook server", e);
    }
    // Медленный fetchById одного запроса не должен блокировать приём остальных.
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/webhook", this::serve);
    if (snapshot != null && controlAuth != null) {
      server.createContext("/control/snapshot", ex -> control(ex, snapshot, controlAuth));
    } else if (snapshot != null) {
      LOG.log(System.Logger.Level.WARNING,
          "/control/snapshot is disabled: token validation is not configured (issuer/JWKS/audience)");
    }
    server.start();
  }

  /** URL endpoint'а, например {@code http://127.0.0.1:41234/webhook}. */
  public URI endpoint() {
    return URI.create("http://" + server.getAddress().getHostString() + ":"
        + server.getAddress().getPort() + "/webhook");
  }

  private void control(HttpExchange ex, Supplier<PollResult> snapshot, ControlTokenValidator auth)
      throws IOException {
    try (ex) {
      if (!"POST".equals(ex.getRequestMethod())) {
        ex.sendResponseHeaders(405, -1);
        return;
      }
      switch (auth.validate(ex.getRequestHeaders().getFirst("Authorization"))) {
        case UNAUTHENTICATED -> {
          ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
          ex.sendResponseHeaders(401, -1);
          return;
        }
        case FORBIDDEN -> {
          ex.sendResponseHeaders(403, -1);
          return;
        }
        case OK -> { }
      }
      int status;
      String body;
      try {
        PollResult r = snapshot.get();
        status = switch (r.outcome()) {
          case ALREADY_RUNNING -> 409;
          case GAVE_UP -> 503;
          default -> 202;
        };
        body = Json.write(java.util.Map.of("outcome", r.outcome(), "appended", r.appended(),
            "syncRunId", r.syncRunId() == null ? "" : r.syncRunId()));
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.ERROR, "snapshot failed: " + e.getClass().getName());
        status = 500;
        body = "{}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
    }
  }

  private void serve(HttpExchange ex) throws IOException {
    try (ex) {
      int status;
      if (!"POST".equals(ex.getRequestMethod())) {
        status = 405;
      } else {
        status = process(ex);
      }
      ex.sendResponseHeaders(status, -1);
    }
  }

  private int process(HttpExchange ex) throws IOException {
    byte[] body;
    try (InputStream in = ex.getRequestBody()) {
      body = in.readNBytes(MAX_BODY_BYTES + 1);
    }
    if (body.length > MAX_BODY_BYTES) {
      return 413;
    }
    try {
      return handler.handle(name -> ex.getRequestHeaders().getFirst(name),
          new String(body, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "webhook processing failed: " + e.getClass().getName());
      return 500;
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
