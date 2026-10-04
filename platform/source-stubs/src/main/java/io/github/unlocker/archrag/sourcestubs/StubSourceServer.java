package io.github.unlocker.archrag.sourcestubs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP-фасад над {@link StubSource} на JDK {@code HttpServer}: слушает loopback на свободном
 * порту, нужен REST-адаптерам и приёмочным тестам.
 *
 * <p>Ручки: {@code GET /changes?cursor=&limit=} (страница {@code ChangePage}) и {@code GET
 * /objects/{type}/{id}} (снимок объекта, 404 для неизвестного). Сбои источника, включённые через
 * {@link StubSource#failNext} или {@link #failOnCall}, отдаются как HTTP-статус с {@code
 * Retry-After} в секундах; timeout (статус 0) отдаётся как 504. Только чтение.
 */
public final class StubSourceServer implements AutoCloseable {

  private static final int DEFAULT_LIMIT = 100;

  private final StubSource source;
  private final HttpServer server;
  private final Map<Integer, SourceUnavailableException> scheduledFailures =
      new ConcurrentHashMap<>();
  private int calls;

  public StubSourceServer(StubSource source) {
    this.source = source;
    try {
      this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
          0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start stub source server", e);
    }
    server.createContext("/changes", this::handleChanges);
    server.createContext("/objects/", this::handleObject);
    server.start();
  }

  /** Базовый URL сервера, например {@code http://127.0.0.1:41234}. */
  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /**
   * Ответить сбоем на вызов с номером {@code callNumber} (нумерация с 1 по обеим ручкам).
   *
   * @param status 429, 5xx или 0 для timeout
   * @param retryAfter значение {@code Retry-After}, либо {@code null}
   */
  public void failOnCall(int callNumber, int status, Duration retryAfter) {
    scheduledFailures.put(callNumber, new SourceUnavailableException(source.system(), status,
        retryAfter));
  }

  private void handleChanges(HttpExchange ex) throws IOException {
    respond(ex, () -> {
      Map<String, String> q = query(ex.getRequestURI());
      String cursor = q.get("cursor");
      int limit = q.containsKey("limit") ? Integer.parseInt(q.get("limit")) : DEFAULT_LIMIT;
      ChangePage page = source.fetchChanges(cursor == null || cursor.isEmpty() ? null : cursor,
          limit);
      return new Reply(200, Json.write(pageJson(page)));
    });
  }

  private void handleObject(HttpExchange ex) throws IOException {
    respond(ex, () -> {
      String[] parts = ex.getRequestURI().getRawPath().substring("/objects/".length()).split("/");
      if (parts.length != 2) {
        return new Reply(404, "{}");
      }
      Optional<SourceChange> found = source.fetchById(decode(parts[0]), decode(parts[1]));
      return found.map(c -> new Reply(200, Json.write(changeJson(c))))
          .orElseGet(() -> new Reply(404, "{}"));
    });
  }

  private void respond(HttpExchange ex, Handler handler) throws IOException {
    try (ex) {
      Reply reply;
      Map<String, String> headers = new LinkedHashMap<>();
      try {
        if (!"GET".equals(ex.getRequestMethod())) {
          reply = new Reply(405, "{}");
        } else {
          failIfScheduled();
          reply = handler.handle();
        }
      } catch (SourceUnavailableException e) {
        reply = new Reply(e.statusCode() == 0 ? 504 : e.statusCode(), "{}");
        if (e.retryAfter() != null) {
          headers.put("Retry-After", Long.toString(e.retryAfter().toSeconds()));
        }
      } catch (IllegalArgumentException e) {
        reply = new Reply(400, "{}");
      }
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
      ex.sendResponseHeaders(reply.status(), body.length);
      ex.getResponseBody().write(body);
    }
  }

  private synchronized void failIfScheduled() {
    SourceUnavailableException failure = scheduledFailures.remove(++calls);
    if (failure != null) {
      throw failure;
    }
  }

  private static Map<String, Object> pageJson(ChangePage page) {
    List<Object> changes = new ArrayList<>();
    page.changes().forEach(c -> changes.add(changeJson(c)));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("changes", changes);
    m.put("nextCursor", page.nextCursor());
    m.put("hasMore", page.hasMore());
    m.put("snapshotComplete", page.snapshotComplete());
    return m;
  }

  private static Map<String, Object> changeJson(SourceChange c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sourceType", c.sourceType());
    m.put("sourceId", c.sourceId());
    m.put("sourceVersion", c.sourceVersion());
    m.put("operation", c.operation());
    m.put("completeness", c.completeness());
    m.put("updatedAt", c.updatedAt());
    m.put("payload", c.payload());
    return m;
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> m = new LinkedHashMap<>();
    String raw = uri.getRawQuery();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          m.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
      }
    }
    return m;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private record Reply(int status, String body) {}

  private interface Handler {
    Reply handle();
  }
}
