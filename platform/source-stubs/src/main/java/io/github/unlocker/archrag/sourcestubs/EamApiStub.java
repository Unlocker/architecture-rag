package io.github.unlocker.archrag.sourcestubs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Заглушка реального EAM API (формат {@code docs/eam-api.yaml}) на JDK {@code HttpServer}. Только чтение, как у
 * адаптера:
 *
 * <ul>
 *   <li>{@code GET /api/<type>/?page=&page_size=&sort=&fields=} — голый массив {@code ArchObject[]} без
 *       {@code total}/{@code next}; страница за концом — пустой массив; {@code page_size} зажат в {@value
 *       #MAX_PAGE_SIZE};
 *   <li>{@code GET /api/<type>/{id}/}; {@code GET /api/types/}, {@code /api/types/{id}/}, {@code
 *       /api/types/{id}/options/?kind=fields|system} — метаданные типов (формат опций — допущение);
 *   <li>заголовок {@code Authorization: Token <token>} обязателен, иначе 401; изменяющие методы — 405.
 * </ul>
 *
 * Значения лимитов и формат метаданных — допущения «не подтверждено» ({@code docs/06-eam-integration.md}).
 */
public final class EamApiStub implements AutoCloseable {

  /** Размер страницы по умолчанию (допущение). */
  public static final int DEFAULT_PAGE_SIZE = 50;
  /** Максимальный размер страницы (допущение). */
  public static final int MAX_PAGE_SIZE = 500;

  private static final Set<String> OPTION_KINDS = Set.of("fields", "system");

  private final EamApiStore store;
  private final byte[] token;
  private final HttpServer server;

  public EamApiStub(EamApiStore store, String token) {
    this(store, token, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
  }

  /** Заглушка на заданном адресе, например {@code 0.0.0.0:8080} для контейнера демо-стенда. */
  public EamApiStub(EamApiStore store, String token, InetSocketAddress address) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token must not be blank");
    }
    this.store = store;
    this.token = token.getBytes(StandardCharsets.UTF_8);
    try {
      this.server = HttpServer.create(address, 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot start EAM API stub", e);
    }
    server.createContext("/api/", this::handle);
    server.start();
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + port());
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      Reply reply;
      try {
        reply = route(ex);
      } catch (IllegalArgumentException e) {
        reply = error(400, e.getMessage());
      }
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(reply.status(), body.length);
      ex.getResponseBody().write(body);
    }
  }

  private Reply route(HttpExchange ex) {
    if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
      return error(401, "Authentication credentials were not provided.");
    }
    if (!"GET".equals(ex.getRequestMethod())) {
      return error(405, "Method not allowed");
    }
    String path = ex.getRequestURI().getRawPath().substring("/api/".length());
    String[] parts = path.split("/");
    Map<String, String> q = query(ex.getRequestURI());
    if (parts.length == 0 || parts[0].isEmpty()) {
      return error(404, "Not found.");
    }
    String type = decode(parts[0]);
    if (type.equals("types")) {
      return types(parts, q);
    }
    if (!store.hasType(type)) {
      return error(404, "Not found.");
    }
    if (parts.length == 1) {
      return list(type, q);
    }
    if (parts.length == 2) {
      return store.find(type, parseId(parts[1])).map(e -> new Reply(200, Json.write(archObject(e, q.get("fields")))))
          .orElseGet(() -> error(404, "Not found."));
    }
    return error(404, "Not found.");
  }

  private Reply list(String type, Map<String, String> q) {
    int page = intParam(q, "page", 1);
    int pageSize = Math.min(intParam(q, "page_size", DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);
    if (page < 1 || pageSize < 1) {
      throw new IllegalArgumentException("page and page_size must be positive");
    }
    List<Object> out = new ArrayList<>();
    store.page(type, q.get("sort"), page, pageSize).forEach(e -> out.add(archObject(e, q.get("fields"))));
    return new Reply(200, Json.write(out));
  }

  private Reply types(String[] parts, Map<String, String> q) {
    if (parts.length == 1) {
      List<Object> out = new ArrayList<>();
      EamApiSeed.TYPE_IDS.keySet().stream().sorted().forEach(t -> out.add(typeObject(t)));
      return new Reply(200, Json.write(out));
    }
    Optional<String> type = EamApiSeed.TYPE_IDS.entrySet().stream()
        .filter(e -> String.valueOf(e.getValue()).equals(parts[1]) || e.getKey().equals(parts[1]))
        .map(Map.Entry::getKey).findFirst();
    if (type.isEmpty()) {
      return error(404, "Not found.");
    }
    if (parts.length == 2) {
      return new Reply(200, Json.write(typeObject(type.get())));
    }
    if (parts.length == 3 && parts[2].equals("options")) {
      String kind = q.get("kind");
      if (kind == null || !OPTION_KINDS.contains(kind)) {
        return error(400, "kind must be one of " + OPTION_KINDS);
      }
      return new Reply(200, Json.write(Map.of("options", EamApiTypeMeta.options(type.get(), kind))));
    }
    return error(404, "Not found.");
  }

  private Map<String, Object> typeObject(String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", EamApiSeed.TYPE_IDS.get(type));
    m.put("ver", 1);
    m.put("datetime", "2026-01-01T00:00:00Z");
    m.put("attrs", StubSources.fields("name", EamApiTypeMeta.title(type), "typeurl", type));
    return m;
  }

  private static Map<String, Object> archObject(EamApiStore.Entry e, String fields) {
    Map<String, Object> attrs = e.attrs();
    if (fields != null && !fields.isBlank()) {
      Set<String> wanted = new HashSet<>(Arrays.asList(fields.split(",")));
      attrs = new LinkedHashMap<>();
      for (Map.Entry<String, Object> a : e.attrs().entrySet()) {
        if (wanted.contains(a.getKey())) {
          attrs.put(a.getKey(), a.getValue());
        }
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", e.id());
    m.put("ver", e.ver());
    m.put("datetime", e.mtime());
    m.put("attrs", attrs);
    return m;
  }

  private boolean authorized(String header) {
    if (header == null || !header.startsWith("Token ")) {
      return false;
    }
    return MessageDigest.isEqual(header.substring("Token ".length()).getBytes(StandardCharsets.UTF_8), token);
  }

  private static long parseId(String raw) {
    try {
      return Long.parseLong(decode(raw));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("id must be an integer");
    }
  }

  private static int intParam(Map<String, String> q, String name, int dflt) {
    if (!q.containsKey(name)) {
      return dflt;
    }
    try {
      return Integer.parseInt(q.get(name));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be an integer");
    }
  }

  private static Reply error(int status, String detail) {
    return new Reply(status, Json.write(Map.of("detail", detail)));
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
}
