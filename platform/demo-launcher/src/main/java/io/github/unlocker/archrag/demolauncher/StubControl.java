package io.github.unlocker.archrag.demolauncher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.adaptercore.Json;
import io.github.unlocker.archrag.sourcestubs.StubSource;
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
 */
final class StubControl implements AutoCloseable {

  static final int MAX_BODY_BYTES = 64 * 1024;

  private final HttpServer server;

  StubControl(StubSource source, InetSocketAddress address) throws IOException {
    this.server = HttpServer.create(address, 0);
    server.createContext("/control/upsert", ex -> handleUpsert(source, ex));
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  private static void handleUpsert(StubSource source, HttpExchange ex) throws IOException {
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
          long version = source.upsert(type, id, payload).sourceVersion();
          status = 200;
          body = "{\"sourceVersion\":" + version + "}";
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
