package io.github.unlocker.archrag.eamadapter;

import io.github.unlocker.archrag.adaptercore.Json;
import io.github.unlocker.archrag.adaptercore.Sleeper;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@link SourceConnector} поверх EAM API в формате {@code docs/eam-api.yaml}: {@code GET /api/<type>/} для
 * {@code platforms}, {@code itproducts}, {@code itsystems} и {@code GET /api/<type>/{id}/}. Только чтение.
 *
 * <p>Snapshot ({@code cursor == null}) читает типы по очереди с {@code sort=id}; конец типа — страница короче
 * {@code page_size}, маркер {@code snapshotComplete} ставится на последней странице последнего типа, а {@code nextCursor}
 * там — водяной знак (максимальный {@code datetime}). Инкремент читает каждый тип с {@code sort=-mtime}, пока не встретит
 * объект, изменённый не позже водяного знака. Перекрытие страниц гасит идемпотентность события
 * {@code poll:<type>/<id>/v<ver>}. Пропуски из-за сдвига страниц при записи компенсирует периодический snapshot.
 *
 * <p>Лимиты (допущение 6 {@code docs/06-eam-integration.md}): {@code page_size} зажат в {@value #MAX_PAGE_SIZE},
 * запросов не больше 10 в секунду, 429 с {@code Retry-After} — одно ожидание и повтор, затем {@link
 * SourceUnavailableException}. Токен в заголовок {@code Authorization: Token}, в логи и исключения не попадает; тела
 * ответов недоверенные и в исключения не копируются.
 */
public final class EamApiConnector implements SourceConnector, AutoCloseable {

  /** Максимальный размер страницы (допущение 6). */
  public static final int MAX_PAGE_SIZE = 500;

  /** Тип EAM для «ИТ-решения»: допущение 1 «не подтверждено»; единственное место выбора в адаптере. */
  public static final String SOLUTION_TYPE = "itproducts";

  /** Тип источника в журнале -> тип EAM API, в порядке чтения. */
  private static final List<Map.Entry<String, String>> TYPES = List.of(
      Map.entry("PLATFORM", "platforms"),
      Map.entry("SOLUTION", SOLUTION_TYPE),
      Map.entry("IT_SYSTEM", "itsystems"));

  private static final Duration MIN_INTERVAL = Duration.ofMillis(100);
  private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

  private final URI baseUri;
  private final String authorization;
  private final int pageSize;
  private final Sleeper sleeper;
  private final LongSupplier nanoClock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private long lastRequestNanos;
  private boolean requested;

  public EamApiConnector(URI baseUri, String token, int pageSize) {
    this(baseUri, token, pageSize, Sleeper.THREAD, System::nanoTime);
  }

  /** Конструктор для тестов: паузы и часы подменяются. */
  EamApiConnector(URI baseUri, String token, int pageSize, Sleeper sleeper, LongSupplier nanoClock) {
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("eam-api token is required");
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
    this.authorization = "Token " + token;
    this.pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
    this.sleeper = sleeper;
    this.nanoClock = nanoClock;
  }

  @Override
  public SourceSystem system() {
    return SourceSystem.EAM;
  }

  @Override
  public ChangePage fetchChanges(String cursor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    int size = Math.min(pageSize, limit);
    EamApiCursor c = cursor == null ? EamApiCursor.snapshotStart(size) : EamApiCursor.parse(cursor);
    if (c.mode() == EamApiCursor.Mode.WATERMARK) {
      c = c.incrementalStart(size);
    }
    boolean snapshot = c.mode() == EamApiCursor.Mode.SNAPSHOT;
    if (c.type() >= TYPES.size()) {
      throw new IllegalArgumentException("malformed eam-api cursor");
    }
    Map.Entry<String, String> type = TYPES.get(c.type());
    String sort = snapshot ? "id" : "-mtime";
    List<Object> objects = list(type.getValue(), c.page(), c.size(), sort);

    List<SourceChange> changes = new ArrayList<>();
    Instant watermark = c.watermark();
    boolean reachedKnown = false;
    for (Object o : objects) {
      SourceChange change = change(type.getKey(), o);
      if (!snapshot && !change.updatedAt().isAfter(c.from())) {
        reachedKnown = true;
        break;
      }
      changes.add(change);
      if (change.updatedAt().isAfter(watermark)) {
        watermark = change.updatedAt();
      }
    }
    boolean typeDone = reachedKnown || objects.size() < c.size();
    if (!typeDone) {
      return new ChangePage(changes, next(c, c.type(), c.page() + 1, watermark), true, false);
    }
    if (c.type() + 1 < TYPES.size()) {
      return new ChangePage(changes, next(c, c.type() + 1, 1, watermark), true, false);
    }
    return new ChangePage(changes, EamApiCursor.watermark(watermark).encode(), false, snapshot);
  }

  @Override
  public Optional<SourceChange> fetchById(String sourceType, String sourceId) {
    String path = TYPES.stream().filter(t -> t.getKey().equals(sourceType)).map(Map.Entry::getValue).findFirst()
        .orElse(null);
    long id;
    try {
      id = Long.parseLong(sourceId);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    if (path == null) {
      return Optional.empty();
    }
    HttpResponse<String> r = get("/api/" + path + "/" + id + "/");
    if (r.statusCode() == 404) {
      return Optional.empty();
    }
    requireOk(r);
    return Optional.of(change(sourceType, parse(r.body())));
  }

  private static String next(EamApiCursor c, int type, int page, Instant watermark) {
    return new EamApiCursor(c.mode(), type, page, c.size(), c.from(), watermark).encode();
  }

  private List<Object> list(String type, int page, int size, String sort) {
    HttpResponse<String> r = get("/api/" + type + "/?page=" + page + "&page_size=" + size + "&sort=" + sort);
    requireOk(r);
    if (parse(r.body()) instanceof List<?> l) {
      return new ArrayList<>(l);
    }
    throw new IllegalStateException("eam answered a non-array list");
  }

  @SuppressWarnings("unchecked")
  private SourceChange change(String sourceType, Object o) {
    if (!(o instanceof Map<?, ?> m)) {
      throw new IllegalStateException("eam answered malformed ArchObject");
    }
    try {
      Map<String, Object> archObject = (Map<String, Object>) m;
      long id = integral(archObject.get("id"));
      long ver = integral(archObject.get("ver"));
      if (!(archObject.get("attrs") instanceof Map<?, ?>)) {
        throw new IllegalStateException("attrs");
      }
      Instant updatedAt =
          archObject.get("datetime") == null ? Instant.EPOCH : Instant.parse((String) archObject.get("datetime"));
      return new SourceChange(sourceType, Long.toString(id), ver, ChangeOperation.UPSERT, Completeness.COMPLETE,
          updatedAt, archObject);
    } catch (RuntimeException e) {
      // Причину без значения поля: объект источника недоверенный.
      throw new IllegalStateException("eam answered malformed ArchObject", e);
    }
  }

  private static long integral(Object v) {
    if (v instanceof Long l) {
      return l;
    }
    throw new IllegalStateException("integer expected");
  }

  private Object parse(String body) {
    try {
      return Json.parse(body);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("eam answered malformed JSON", e);
    }
  }

  private HttpResponse<String> get(String pathAndQuery) {
    HttpResponse<String> r = send(pathAndQuery);
    if (r.statusCode() == 429) {
      Duration wait = retryAfter(r);
      if (wait != null) {
        pause(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
        r = send(pathAndQuery);
      }
    }
    int status = r.statusCode();
    if (status == 429 || status >= 500) {
      throw new SourceUnavailableException(SourceSystem.EAM, status, retryAfter(r));
    }
    return r;
  }

  private HttpResponse<String> send(String pathAndQuery) {
    throttle();
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUri.toString().replaceAll("/+$", "") + pathAndQuery))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("Authorization", authorization)
            .GET()
            .build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Timeout и обрыв соединения: источник недоступен, повторяем с backoff poller'а.
      throw new SourceUnavailableException(SourceSystem.EAM, 0, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading eam");
    }
  }

  private void requireOk(HttpResponse<String> r) {
    if (r.statusCode() != 200) {
      // 401/403 и прочее: повтор не поможет, тело ответа в сообщение не попадает.
      throw new IllegalStateException("eam answered unexpected status " + r.statusCode());
    }
  }

  /** Не больше 10 запросов в секунду: пауза до {@link #MIN_INTERVAL} после предыдущего запроса. */
  private synchronized void throttle() {
    long now = nanoClock.getAsLong();
    if (requested) {
      long wait = lastRequestNanos + MIN_INTERVAL.toNanos() - now;
      if (wait > 0) {
        pause(Duration.ofNanos(wait));
        now += wait;
      }
    }
    requested = true;
    lastRequestNanos = now;
  }

  private void pause(Duration d) {
    try {
      sleeper.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading eam");
    }
  }

  private static Duration retryAfter(HttpResponse<String> r) {
    return r.headers().firstValue("Retry-After").map(v -> {
      try {
        long seconds = Long.parseLong(v.trim());
        return seconds < 0 ? null : Duration.ofSeconds(seconds);
      } catch (NumberFormatException e) {
        return null;
      }
    }).orElse(null);
  }

  @Override
  public void close() {
    http.close();
  }
}
