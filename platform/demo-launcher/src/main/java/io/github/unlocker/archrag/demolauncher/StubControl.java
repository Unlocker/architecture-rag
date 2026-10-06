package io.github.unlocker.archrag.demolauncher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.adaptercore.Json;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubWebhookSender;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Управляющий вход заглушки демо-стенда: {@code POST /control/upsert} с телом {@code {"type","id","payload"}} вызывает
 * {@link StubSource#upsert}. Поднимается только в режиме {@code stub} на отдельном порту, наружу не публикуется и
 * через proxy не отдаётся: вызов делается из контейнера ({@code docker compose exec stub-eam curl ...}). Через него
 * сценарий backup/restore вносит изменения в источник. Тело — недоверенный ввод: размер ограничен, ошибки разбора
 * дают 400 без эха содержимого.
 *
 * <p>Если задан {@link StubWebhookSender}, после успешного upsert заглушка шлёт адаптеру подписанный webhook об этом
 * изменении (последнее событие {@code (type, id)} из {@link StubSource#webhookEvents()}); тело {@code "notify":false}
 * отключает отправку для вызова. Результат отражается в поле {@code webhookStatus} ответа: HTTP-статус адаптера или
 * {@code 0}, если доставить не удалось. Сбой доставки не превращает upsert в ошибку: изменение в источнике уже
 * произошло, его догонит polling. Поле отсутствует, если webhook не отправлялся.
 */
final class StubControl implements AutoCloseable {

  static final int MAX_BODY_BYTES = 64 * 1024;

  private static final System.Logger LOG = System.getLogger(StubControl.class.getName());

  private final HttpServer server;

  StubControl(StubSource source, InetSocketAddress address) throws IOException {
    this(source, address, null);
  }

  /** @param webhooks отправитель webhook адаптеру или {@code null}, если webhook не нужен */
  StubControl(StubSource source, InetSocketAddress address, StubWebhookSender webhooks) throws IOException {
    this.server = HttpServer.create(address, 0);
    server.createContext("/control/upsert", ex -> handleUpsert(source, webhooks, ex));
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  private static void handleUpsert(StubSource source, StubWebhookSender webhooks, HttpExchange ex) throws IOException {
    try (ex) {
      int status;
      String body;
      if (!"POST".equals(ex.getRequestMethod())) {
        status = 405;
        body = "{\"error\":\"POST expected\"}";
      } else {
        try {
          Map<String, Object> request = Json.parseObject(readBody(ex.getRequestBody()));
          String type = text(request, "type");
          String id = text(request, "id");
          if (!(request.get("payload") instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("payload must be an object");
          }
          Map<String, Object> payload = new LinkedHashMap<>();
          raw.forEach((k, v) -> payload.put(String.valueOf(k), v));
          boolean notify = !Boolean.FALSE.equals(request.get("notify"));
          long version = source.upsert(type, id, payload).sourceVersion();
          status = 200;
          String webhookField = webhooks != null && notify ? ",\"webhookStatus\":" + notifyAdapter(source, webhooks, type, id) : "";
          body = "{\"sourceVersion\":" + version + webhookField + "}";
        } catch (IllegalArgumentException e) {
          status = 400;
          body = "{\"error\":\"invalid request\"}";
        }
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(status, bytes.length);
      ex.getResponseBody().write(bytes);
    }
  }

  /** Шлёт webhook о последнем изменении объекта; возвращает HTTP-статус адаптера или 0 при сбое доставки. */
  private static int notifyAdapter(StubSource source, StubWebhookSender webhooks, String type, String id) {
    WebhookEvent event = source.webhookEvents().reversed().stream()
        .filter(e -> e.sourceType().equals(type) && e.sourceId().equals(id)).findFirst().orElse(null);
    if (event == null) {
      return 0;
    }
    try {
      int status = webhooks.send(event);
      LOG.log(System.Logger.Level.INFO, "webhook " + event.eventId() + " -> adapter status " + status);
      return status;
    } catch (IllegalStateException e) {
      LOG.log(System.Logger.Level.WARNING, "webhook " + event.eventId() + " not delivered: " + e.getMessage());
      return 0;
    }
  }

  private static String text(Map<String, Object> request, String name) {
    if (request.get(name) instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException(name + " must be a non-empty string");
  }

  private static String readBody(InputStream in) throws IOException {
    byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("body too large");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
