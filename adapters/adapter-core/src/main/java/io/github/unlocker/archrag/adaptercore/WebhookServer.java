package io.github.unlocker.archrag.adaptercore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-привязка {@link WebhookHandler} на JDK {@code HttpServer}: {@code POST /webhook}. Тело
 * ограничено {@link #MAX_BODY_BYTES}. Ответ всегда с пустым телом, чтобы не раскрывать детали
 * проверки подписи.
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
    this.handler = handler;
    try {
      this.server = HttpServer.create(address, 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start webhook server", e);
    }
    server.createContext("/webhook", this::serve);
    server.start();
  }

  /** URL endpoint'а, например {@code http://127.0.0.1:41234/webhook}. */
  public URI endpoint() {
    return URI.create("http://" + server.getAddress().getHostString() + ":"
        + server.getAddress().getPort() + "/webhook");
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
