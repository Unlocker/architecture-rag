package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SourceConnector} поверх REST-API источника: {@code GET /changes?cursor=&limit=} и {@code
 * GET /objects/{type}/{id}}. Только чтение.
 *
 * <p>429, 5xx и сетевые сбои превращаются в {@link SourceUnavailableException} ({@code
 * Retry-After} в секундах учитывается, timeout и обрыв соединения — статус 0). Ответ 404 на объект
 * означает «источник объект не знает». Любой другой неожиданный ответ — {@link
 * IllegalStateException} без тела ответа: оно недоверенное.
 */
public final class HttpSourceConnector implements SourceConnector, AutoCloseable {

  private final SourceSystem system;
  private final URI baseUri;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public HttpSourceConnector(SourceSystem system, URI baseUri) {
    this.system = system;
    this.baseUri = baseUri;
  }

  @Override
  public SourceSystem system() {
    return system;
  }

  @Override
  public ChangePage fetchChanges(String cursor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    String query = "?limit=" + limit + (cursor == null ? "" : "&cursor=" + enc(cursor));
    HttpResponse<String> r = get("/changes" + query);
    requireOk(r);
    Map<String, Object> doc = parse(r.body());
    List<SourceChange> changes = new ArrayList<>();
    if (doc.get("changes") instanceof List<?> raw) {
      for (Object o : raw) {
        changes.add(change(o));
      }
    }
    String next = doc.get("nextCursor") instanceof String c ? c : null;
    return new ChangePage(changes, next, Boolean.TRUE.equals(doc.get("hasMore")),
        Boolean.TRUE.equals(doc.get("snapshotComplete")));
  }

  @Override
  public Optional<SourceChange> fetchById(String sourceType, String sourceId) {
    HttpResponse<String> r = get("/objects/" + enc(sourceType) + "/" + enc(sourceId));
    if (r.statusCode() == 404) {
      return Optional.empty();
    }
    requireOk(r);
    return Optional.of(change(parse(r.body())));
  }

  private HttpResponse<String> get(String path) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUri.toString().replaceAll("/+$", "") + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> r;
    try {
      r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Timeout и обрыв соединения: источник недоступен, повторяем с backoff.
      throw new SourceUnavailableException(system, 0, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + system.code());
    }
    int status = r.statusCode();
    if (status == 429 || status >= 500) {
      throw new SourceUnavailableException(system, status, retryAfter(r));
    }
    return r;
  }

  private void requireOk(HttpResponse<String> r) {
    if (r.statusCode() != 200) {
      throw new IllegalStateException(system.code() + " answered unexpected status " + r.statusCode());
    }
  }

  private static Duration retryAfter(HttpResponse<String> r) {
    return r.headers().firstValue("Retry-After").map(v -> {
      try {
        long seconds = Long.parseLong(v.trim());
        return seconds < 0 ? null : Duration.ofSeconds(seconds);
      } catch (NumberFormatException e) {
        return null; // формат HTTP-date не поддерживаем: падаем обратно на backoff
      }
    }).orElse(null);
  }

  private Map<String, Object> parse(String body) {
    try {
      return Json.parseObject(body);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(system.code() + " answered malformed JSON", e);
    }
  }

  @SuppressWarnings("unchecked")
  private SourceChange change(Object o) {
    if (!(o instanceof Map<?, ?>)) {
      throw new IllegalStateException(system.code() + " answered malformed change");
    }
    Map<String, Object> m = (Map<String, Object>) o;
    try {
      return new SourceChange(
          (String) m.get("sourceType"),
          (String) m.get("sourceId"),
          ((Number) m.get("sourceVersion")).longValue(),
          ChangeOperation.valueOf((String) m.get("operation")),
          Completeness.valueOf((String) m.get("completeness")),
          Instant.parse((String) m.get("updatedAt")),
          m.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of());
    } catch (RuntimeException e) {
      throw new IllegalStateException(system.code() + " answered malformed change", e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  @Override
  public void close() {
    http.close();
  }
}
