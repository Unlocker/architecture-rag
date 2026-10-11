package io.github.unlocker.archrag.demolauncher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.adaptercore.Json;
import io.github.unlocker.archrag.sourcestubs.EamApiStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Управляющий вход заглушки {@code eam-api-stub} на отдельном порту, по образцу {@link StubControl}:
 * {@code POST /control/upsert} с телом {@code {"type","id","attrs"}} создаёт либо обновляет объект ({@code ver} растёт),
 * {@code POST /control/archive} с телом {@code {"type","id"}} убирает его из основного списка. Методы {@code /api/**}
 * заглушки остаются только для чтения. Порт не публикуется и не проксируется: вызов делается из контейнера. Через него
 * сценарии стенда ({@code measure.sh}, {@code backup-restore-check.sh}) меняют источник. {@code type} ограничен
 * известными типами; тело — недоверенный ввод: размер ограничен, ошибки разбора дают 400 без эха содержимого.
 */
final class EamApiControl implements AutoCloseable {

  static final int MAX_BODY_BYTES = 64 * 1024;

  private static final Set<String> TYPES = Set.of("platforms", "itproducts", "itsystems");

  private final HttpServer server;

  EamApiControl(EamApiStore store, InetSocketAddress address) throws IOException {
    this.server = HttpServer.create(address, 0);
    server.createContext("/control/upsert", ex -> handle(ex, request -> upsert(store, request)));
    server.createContext("/control/archive", ex -> handle(ex, request -> archive(store, request)));
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  private static String upsert(EamApiStore store, Map<String, Object> request) {
    String type = type(request);
    long id = id(request);
    if (!(request.get("attrs") instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("attrs must be an object");
    }
    Map<String, Object> attrs = new LinkedHashMap<>();
    raw.forEach((k, v) -> attrs.put(String.valueOf(k), v));
    store.upsert(type, id, attrs);
    return "{\"ver\":" + store.version(type, id).orElseThrow() + "}";
  }

  private static String archive(EamApiStore store, Map<String, Object> request) {
    store.archive(type(request), id(request));
    return "{}";
  }

  private static String type(Map<String, Object> request) {
    if (request.get("type") instanceof String s && TYPES.contains(s)) {
      return s;
    }
    throw new IllegalArgumentException("type must be one of " + TYPES);
  }

  private static long id(Map<String, Object> request) {
    if (request.get("id") instanceof Long id) {
      return id;
    }
    throw new IllegalArgumentException("id must be an integer");
  }

  private interface Action {
    String run(Map<String, Object> request);
  }

  private static void handle(HttpExchange ex, Action action) throws IOException {
    try (ex) {
      int status;
      String body;
      if (!"POST".equals(ex.getRequestMethod())) {
        status = 405;
        body = "{\"error\":\"POST expected\"}";
      } else {
        try {
          body = action.run(Json.parseObject(readBody(ex.getRequestBody())));
          status = 200;
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
