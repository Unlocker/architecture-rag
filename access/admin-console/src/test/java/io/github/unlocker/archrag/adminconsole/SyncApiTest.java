package io.github.unlocker.archrag.adminconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.adminconsole.pg.ConsoleReadRepository;
import io.github.unlocker.archrag.adminconsole.pg.Page;
import io.github.unlocker.archrag.adminconsole.pg.PageRequest;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Event;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Эндпоинты синхронизации, identity и аудита: безопасность, валидация, формы ответов. PostgreSQL и граф подменены. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(SyncApiTest.FixedClock.class)
class SyncApiTest {

  static final List<String> ROUTES =
      List.of(
          "/api/sync/sources",
          "/api/sync/events",
          "/api/sync/events/eam/e-1",
          "/api/sync/dlq",
          "/api/identity/conflicts",
          "/api/identity/candidates",
          "/api/audit");

  @LocalServerPort int port;
  @MockitoBean GraphQueryExecutor executor;
  @MockitoBean ConsoleReadRepository repository;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private ResponseEntity<String> get(String path, String token) {
    return RestClient.builder()
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .get()
        .uri(java.net.URI.create("http://localhost:" + port + path))
        .headers(h -> {
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .retrieve()
        .toEntity(String.class);
  }

  private static JsonNode json(ResponseEntity<String> res) {
    return new JsonMapper().readTree(res.getBody());
  }

  @Test
  void everyRouteRequiresTokenAndAdminScope() {
    for (String route : ROUTES) {
      assertThat(get(route, null).getStatusCode().value()).as(route).isEqualTo(401);
      assertThat(get(route, TestJwt.token("architecture.read")).getStatusCode().value()).as(route).isEqualTo(403);
    }
    verify(repository, never()).events(any(), any(), any(), any(), any());
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void sizeAboveLimitAndNegativePageAreRejected() {
    String token = TestJwt.token("architecture.admin");
    for (String route : List.of("/api/sync/events", "/api/sync/dlq", "/api/identity/conflicts",
        "/api/identity/candidates", "/api/audit")) {
      assertThat(get(route + "?size=201", token).getStatusCode().value()).as(route).isEqualTo(400);
      assertThat(get(route + "?size=0", token).getStatusCode().value()).as(route).isEqualTo(400);
      assertThat(get(route + "?page=-1", token).getStatusCode().value()).as(route).isEqualTo(400);
    }
  }

  @Test
  void unknownSourceAndStatusAreRejectedWithoutEchoingInput() {
    String token = TestJwt.token("architecture.admin");

    var source = get("/api/sync/events?source=evil'%20OR%201=1", token);
    var status = get("/api/sync/events?status=NOPE", token);

    assertThat(source.getStatusCode().value()).isEqualTo(400);
    assertThat(source.getBody()).doesNotContain("evil");
    assertThat(status.getStatusCode().value()).isEqualTo(400);
    assertThat(status.getBody()).doesNotContain("NOPE");
    assertThat(get("/api/sync/events?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z", token)
            .getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/identity/conflicts?gid=zzz", token).getStatusCode().value()).isEqualTo(400);
    verify(repository, never()).events(any(), any(), any(), any(), any());
  }

  @Test
  void filtersAreMappedToStoredValuesAndResponseHasNoPayloadField() {
    var event = new Event("eam", "e-1", "t", null, "IT_SYSTEM", "1", "3", null, "run", "1", "PROJECTED", 0, null,
        "raw/eam/abc", "abc", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"));
    when(repository.events(any(), any(), any(), any(), any())).thenReturn(new Page<>(List.of(event), 1, 5, 11));

    var res = get("/api/sync/events?source=eam&status=PROJECTED&page=1&size=5&from=2026-01-01T00:00:00Z",
        TestJwt.token("architecture.admin"));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    verify(repository)
        .events(org.mockito.ArgumentMatchers.eq("urn:corp:eam"), org.mockito.ArgumentMatchers.eq("PROJECTED"),
            org.mockito.ArgumentMatchers.eq(Instant.parse("2026-01-01T00:00:00Z")), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(PageRequest.of(1, 5)));
    JsonNode item = json(res).get("items").get(0);
    assertThat(item.get("payloadRef").asString()).isEqualTo("raw/eam/abc");
    assertThat(item.get("contentHash").asString()).isEqualTo("abc");
    assertThat(item.propertyNames()).doesNotContain("payload", "data");
    assertThat(json(res).get("total").asLong()).isEqualTo(11);
  }

  @Test
  void responseTypesDeclareNoPayloadComponent() {
    for (Class<?> type : io.github.unlocker.archrag.adminconsole.pg.Rows.class.getDeclaredClasses()) {
      if (type.isRecord()) {
        assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
            .as(type.getSimpleName())
            .doesNotContain("payload", "data", "request");
      }
    }
  }

  @Test
  void sourcesAlwaysListAllFourSourcesWithZeroCounts() {
    when(repository.checkpoints()).thenReturn(Map.of());
    when(repository.eventCounts()).thenReturn(Map.of());
    when(repository.lastProjected()).thenReturn(Map.of());
    when(executor.execute(any(), any(), any()))
        .thenReturn(new QueryResult("console_latest_sync_runs", List.of(), false, 0, Duration.ZERO));

    var res = get("/api/sync/sources", TestJwt.token("architecture.admin"));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(res);
    assertThat(body.size()).isEqualTo(4);
    assertThat(body.get(0).get("source").asString()).isEqualTo("eam");
    assertThat(body.get(3).get("source").asString()).isEqualTo("deploymap");
    assertThat(body.get(0).get("eventsByStatus").get("PROJECTED").asLong()).isZero();
    assertThat(body.get(0).get("lagSeconds").isNull()).isTrue();
    assertThat(body.get(0).get("lastSyncRun").isNull()).isTrue();
  }

  @Test
  void unknownEventIsNotFoundAndUnknownSourceIsRejected() {
    String token = TestJwt.token("architecture.admin");
    when(repository.eventsById(any(), any())).thenReturn(List.of());

    assertThat(get("/api/sync/events/eam/e-1", token).getStatusCode().value()).isEqualTo(404);
    assertThat(get("/api/sync/events/bogus/e-1", token).getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void lagIsComputedFromInjectedClock() {
    when(repository.checkpoints()).thenReturn(Map.of());
    when(repository.eventCounts()).thenReturn(Map.of());
    when(repository.lastProjected()).thenReturn(Map.of("eam", NOW.minusSeconds(90)));
    when(executor.execute(any(), any(), any()))
        .thenReturn(new QueryResult("console_latest_sync_runs", List.of(), false, 0, Duration.ZERO));

    JsonNode body = json(get("/api/sync/sources", TestJwt.token("architecture.admin")));

    assertThat(body.get(0).get("lagSeconds").asLong()).isEqualTo(90);
    assertThat(body.get(1).get("lagSeconds").isNull()).isTrue();
  }

  static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

  @org.springframework.boot.test.context.TestConfiguration
  static class FixedClock {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock fixedClock() {
      return java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    }
  }
}
