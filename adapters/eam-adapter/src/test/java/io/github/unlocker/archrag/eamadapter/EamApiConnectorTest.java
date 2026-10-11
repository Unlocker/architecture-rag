package io.github.unlocker.archrag.eamadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStore;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EamApiConnectorTest {

  private static final String TOKEN = "t0ken";

  private Instant now = Instant.parse("2026-10-11T10:00:00Z");
  private final Clock clock = new Clock() {
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  };
  private long nanos;
  private final List<Duration> pauses = new ArrayList<>();
  private EamApiStore store;
  private EamApiStub stub;
  private EamApiConnector connector;

  @BeforeEach
  void setUp() {
    store = EamApiSeed.seeded(clock);
    stub = new EamApiStub(store, TOKEN);
    connector = connector(stub.baseUri(), TOKEN, 500);
  }

  @AfterEach
  void tearDown() {
    connector.close();
    stub.close();
  }

  private EamApiConnector connector(URI uri, String token, int pageSize) {
    return new EamApiConnector(uri, token, pageSize, d -> { pauses.add(d); nanos += d.toNanos(); }, () -> nanos);
  }

  /** Читает snapshot до конца; возвращает все изменения и последнюю страницу. */
  private record Read(List<SourceChange> changes, ChangePage last, int pages) {}

  private Read readSnapshot(EamApiConnector c, int limit) {
    List<SourceChange> all = new ArrayList<>();
    ChangePage page = c.fetchChanges(null, limit);
    int pages = 1;
    all.addAll(page.changes());
    while (page.hasMore()) {
      page = c.fetchChanges(page.nextCursor(), limit);
      all.addAll(page.changes());
      pages++;
    }
    return new Read(all, page, pages);
  }

  private static List<String> keys(List<SourceChange> changes) {
    return changes.stream().map(c -> c.sourceType() + "/" + c.sourceId()).toList();
  }

  @Test
  void snapshotReadsThreeTypesWithIntegerIdAsSourceId() {
    Read r = readSnapshot(connector, 100);
    assertThat(keys(r.changes())).containsExactly("PLATFORM/3101", "PLATFORM/3102", "SOLUTION/7201",
        "SOLUTION/7202", "IT_SYSTEM/5101", "IT_SYSTEM/5102");
    assertThat(r.last().snapshotComplete()).isTrue();
    assertThat(r.last().hasMore()).isFalse();
    SourceChange payments = r.changes().get(4);
    assertThat(payments.sourceVersion()).isEqualTo(1);
    assertThat(payments.operation()).isEqualTo(ChangeOperation.UPSERT);
    assertThat(payments.updatedAt()).isEqualTo(now);
    assertThat(payments.payload()).containsKeys("id", "ver", "datetime", "attrs");
  }

  @Test
  void paginationWithFullLastPageContinuesToEmptyPage() {
    // 2 платформы при page_size=2: первая страница полная, вторая пустая — конец типа.
    Read r = readSnapshot(connector(stub.baseUri(), TOKEN, 2), 2);
    assertThat(r.changes()).hasSize(6);
    assertThat(r.last().snapshotComplete()).isTrue();
    assertThat(r.pages()).isEqualTo(6); // platforms: 2 стр., itproducts: 2, itsystems: 2
  }

  @Test
  void paginationWithPartialLastPageStopsImmediately() {
    store.upsert("platforms", 3103, Map.of("name", "Kafka"));
    Read r = readSnapshot(connector(stub.baseUri(), TOKEN, 2), 2);
    assertThat(keys(r.changes())).contains("PLATFORM/3103").hasSize(7);
    assertThat(r.last().snapshotComplete()).isTrue();
  }

  @Test
  void snapshotCompleteOnlyOnLastPageOfLastType() {
    ChangePage p = connector.fetchChanges(null, 100);
    int completes = 0;
    while (true) {
      if (p.snapshotComplete()) {
        completes++;
        assertThat(p.hasMore()).isFalse();
      }
      if (!p.hasMore()) {
        break;
      }
      assertThat(p.snapshotComplete()).isFalse();
      p = connector.fetchChanges(p.nextCursor(), 100);
    }
    assertThat(completes).isEqualTo(1);
  }

  @Test
  void incrementalReturnsOnlyObjectsChangedSinceWatermark() {
    String watermark = readSnapshot(connector, 100).last().nextCursor();
    assertThat(drain(watermark)).isEmpty();

    now = now.plusSeconds(60);
    store.upsert("platforms", 3102, Map.of("name", "PostgreSQL Platform 2", "status", "ACTIVE"));
    store.upsert("itsystems", 5101, Map.of("name", "Payments Core", "eam_id", "EAM-1042"));
    List<SourceChange> changed = drain(watermark);
    assertThat(keys(changed)).containsExactly("PLATFORM/3102", "IT_SYSTEM/5101");
    assertThat(changed).extracting(SourceChange::sourceVersion).containsExactly(2L, 2L);
  }

  @Test
  void incrementalNextCursorMovesWatermarkForward() {
    String watermark = readSnapshot(connector, 100).last().nextCursor();
    now = now.plusSeconds(60);
    store.upsert("platforms", 3102, Map.of("name", "X"));
    ChangePage p = connector.fetchChanges(watermark, 100);
    while (p.hasMore()) {
      p = connector.fetchChanges(p.nextCursor(), 100);
    }
    assertThat(p.snapshotComplete()).isFalse();
    assertThat(p.nextCursor()).isEqualTo("W," + now);
    assertThat(drain(p.nextCursor())).isEmpty();
  }

  private List<SourceChange> drain(String cursor) {
    List<SourceChange> all = new ArrayList<>();
    ChangePage p = connector.fetchChanges(cursor, 100);
    all.addAll(p.changes());
    while (p.hasMore()) {
      p = connector.fetchChanges(p.nextCursor(), 100);
      all.addAll(p.changes());
    }
    return all;
  }

  @Test
  void archivedObjectDisappearsFromSnapshot() {
    store.archive("platforms", 3102);
    assertThat(keys(readSnapshot(connector, 100).changes())).doesNotContain("PLATFORM/3102");
  }

  @Test
  void wrongTokenIsRejectedWithoutLeakingIt() {
    EamApiConnector bad = connector(stub.baseUri(), "wrong-token", 100);
    assertThatThrownBy(() -> bad.fetchChanges(null, 100))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("401").hasMessageNotContaining("wrong-token");
    bad.close();
  }

  @Test
  void blankTokenIsRefused() {
    assertThatThrownBy(() -> connector(stub.baseUri(), " ", 100)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fetchByIdReadsObjectAndReturnsEmptyFor404() {
    SourceChange c = connector.fetchById("IT_SYSTEM", "5101").orElseThrow();
    assertThat(c.sourceId()).isEqualTo("5101");
    assertThat(connector.fetchById("IT_SYSTEM", "999999")).isEmpty();
    assertThat(connector.fetchById("TEAM", "TEAM-PAY")).isEmpty();
    assertThat(connector.fetchById("IT_SYSTEM", "../types")).isEmpty();
  }

  @Test
  void referencesAcceptListSingleValueAndNullInPayload() {
    store.upsert("itsystems", 5201, Map.of("name", "A", "solution", 7201L));
    store.upsert("itsystems", 5202, Map.of("name", "B", "solution", List.of(7201L, 7202L)));
    java.util.Map<String, Object> nullRef = new java.util.LinkedHashMap<>();
    nullRef.put("name", "C");
    nullRef.put("solution", null);
    store.upsert("itsystems", 5203, nullRef);
    // Коннектор отдаёт attrs как есть, разбор форм ссылок — задача нормализатора.
    Read r = readSnapshot(connector, 100);
    assertThat(keys(r.changes())).contains("IT_SYSTEM/5201", "IT_SYSTEM/5202", "IT_SYSTEM/5203");
  }

  @Test
  void malformedIntegerIdIsRejected() throws Exception {
    try (Fake fake = new Fake(ex -> reply(ex, 200, "[{\"id\":\"A\",\"ver\":1,\"attrs\":{}}]", null))) {
      EamApiConnector c = connector(fake.uri(), TOKEN, 100);
      assertThatThrownBy(() -> c.fetchChanges(null, 100)).isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("A\"");
      c.close();
    }
  }

  @Test
  void rateLimitWithRetryAfterWaitsOnceAndRetries() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fake fake = new Fake(ex -> {
      if (calls.incrementAndGet() == 1) {
        reply(ex, 429, "{}", "3");
      } else {
        reply(ex, 200, "[]", null);
      }
    })) {
      EamApiConnector c = connector(fake.uri(), TOKEN, 100);
      ChangePage p = c.fetchChanges(null, 100);
      assertThat(p.changes()).isEmpty();
      assertThat(calls.get()).isEqualTo(2);
      assertThat(pauses).contains(Duration.ofSeconds(3));
      c.close();
    }
  }

  @Test
  void repeatedRateLimitSurfacesAsSourceUnavailable() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fake fake = new Fake(ex -> {
      calls.incrementAndGet();
      reply(ex, 429, "{}", "1");
    })) {
      EamApiConnector c = connector(fake.uri(), TOKEN, 100);
      assertThatThrownBy(() -> c.fetchChanges(null, 100)).isInstanceOfSatisfying(SourceUnavailableException.class,
          e -> {
            assertThat(e.isRateLimited()).isTrue();
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
          });
      assertThat(calls.get()).isEqualTo(2);
      c.close();
    }
  }

  @Test
  void rateLimitWithoutRetryAfterAndServerErrorAreNotRetriedInside() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fake fake = new Fake(ex -> {
      calls.incrementAndGet();
      reply(ex, calls.get() == 1 ? 429 : 503, "{}", null);
    })) {
      EamApiConnector c = connector(fake.uri(), TOKEN, 100);
      assertThatThrownBy(() -> c.fetchChanges(null, 100)).isInstanceOf(SourceUnavailableException.class);
      assertThatThrownBy(() -> c.fetchChanges(null, 100)).isInstanceOfSatisfying(SourceUnavailableException.class,
          e -> assertThat(e.statusCode()).isEqualTo(503));
      assertThat(calls.get()).isEqualTo(2);
      c.close();
    }
  }

  @Test
  void requestsAreSpacedToTenPerSecond() {
    // Часы идут только за счёт пауз, поэтому каждый следующий запрос обязан ждать полный интервал.
    readSnapshot(connector, 100);
    assertThat(pauses).isNotEmpty().allMatch(d -> d.equals(Duration.ofMillis(100)));
  }

  @Test
  void pageSizeIsClampedTo500() throws Exception {
    List<String> queries = new ArrayList<>();
    try (Fake fake = new Fake(ex -> {
      queries.add(ex.getRequestURI().getRawQuery());
      reply(ex, 200, "[]", null);
    })) {
      EamApiConnector c = connector(fake.uri(), TOKEN, 10_000);
      c.fetchChanges(null, 10_000);
      assertThat(queries.get(0)).contains("page_size=500").contains("sort=id");
      c.close();
    }
  }

  @Test
  void malformedCursorIsRejected() {
    assertThatThrownBy(() -> connector.fetchChanges("garbage", 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> connector.fetchChanges("S,9,1,10,2026-01-01T00:00:00Z", 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body, String retryAfter)
      throws java.io.IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    if (retryAfter != null) {
      ex.getResponseHeaders().add("Retry-After", retryAfter);
    }
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  /** Минимальный сервер с произвольным обработчиком для сценариев, которых нет у {@link EamApiStub}. */
  private static final class Fake implements AutoCloseable {
    private final HttpServer server;

    Fake(com.sun.net.httpserver.HttpHandler handler) throws java.io.IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", handler);
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
