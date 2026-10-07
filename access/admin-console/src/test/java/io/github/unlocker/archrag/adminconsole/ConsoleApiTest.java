package io.github.unlocker.archrag.adminconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

/** API консоли целиком: безопасность, валидация, формы ответов, аудит. Граф подменён, Docker не нужен. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConsoleApiTest {

  static final String GID = "11111111-1111-1111-1111-111111111111";
  static final String ADMIN = "architecture.admin";

  @LocalServerPort int port;
  @MockitoBean GraphQueryExecutor executor;

  private ListAppender<ILoggingEvent> appender;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  @BeforeEach
  void captureAudit() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger("archrag.audit.console")).addAppender(appender);
  }

  @AfterEach
  void release() {
    ((Logger) LoggerFactory.getLogger("archrag.audit.console")).detachAppender(appender);
  }

  private ResponseEntity<String> get(String path, String token) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .get()
        // URI, а не шаблон: процентное кодирование в тестовых путях должно уходить как есть.
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

  private static QueryResult rows(String templateId, boolean truncated, Map<String, Object>... rows) {
    return new QueryResult(templateId, List.of(rows), truncated, rows.length, Duration.ZERO);
  }

  // --- безопасность ---

  @Test
  void apiWithoutTokenIsUnauthorized() {
    assertThat(get("/api/graph/stats", null).getStatusCode().value()).isEqualTo(401);
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void apiWithoutAdminScopeIsForbidden() {
    var res = get("/api/graph/stats", TestJwt.token("architecture.read"));

    assertThat(res.getStatusCode().value()).isEqualTo(403);
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void apiWithForeignAudienceIsUnauthorized() {
    String foreign =
        TestJwt.token(
            List.of(ADMIN),
            "urn:archrag:other",
            TestJwt.ISSUER,
            java.time.Instant.now().plus(Duration.ofMinutes(5)));

    assertThat(get("/api/graph/stats", foreign).getStatusCode().value()).isEqualTo(401);
    verify(executor, never()).execute(any(), any(), any());
  }

  @Test
  void unknownApiPathWithScopeIsNotFoundAndNotHtml() {
    var res = get("/api/unknown", TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(404);
    assertThat(res.getBody() == null ? "" : res.getBody()).doesNotContain("<html");
  }

  @Test
  void otherActuatorEndpointsAndWritesAreDenied() {
    assertThat(get("/actuator/info", null).getStatusCode().value()).isEqualTo(401);
    assertThat(get("/actuator/env", TestJwt.token(ADMIN)).getStatusCode().is2xxSuccessful()).isFalse();
    var post =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (req, res) -> {})
            .build()
            .post()
            .uri("/api/graph/stats")
            .headers(h -> h.setBearerAuth(TestJwt.token(ADMIN)))
            .retrieve()
            .toBodilessEntity();
    assertThat(post.getStatusCode().is2xxSuccessful()).isFalse();
  }

  @Test
  void securityHeadersArePresentOnStaticAndApi() {
    for (String path : new String[] {"/", "/api/graph/stats"}) {
      var headers = get(path, null).getHeaders();
      assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(headers.getFirst("Content-Security-Policy"))
          .contains("default-src 'self'")
          .contains("connect-src 'self' https://idp.test;")
          .contains("frame-ancestors 'none'");
    }
  }

  // --- статика и конфигурация ---

  @Test
  void consoleConfigIsPublicAndHasNoSecrets() {
    var res = get("/console-config.json", null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(res);
    assertThat(body.get("issuer").asString()).isEqualTo(TestJwt.ISSUER);
    assertThat(body.get("clientId").asString()).isEqualTo("archrag-admin-console");
    assertThat(body.get("scope").asString()).contains(ADMIN);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("issuer", "clientId", "scope");
  }

  @Test
  void staticFilesAndSpaRoutesArePublicAndFallBackToIndex() {
    String index = get("/", null).getBody();
    assertThat(index).contains("id=\"root\"");
    Matcher script = Pattern.compile("<script[^>]*\\ssrc=\"([^\"]+)\"").matcher(index);
    assertThat(script.find()).as("бандл подключён <script src>").isTrue();
    assertThat(get("/graph/nodes/abc", null).getBody()).contains("id=\"root\"").contains(script.group(1));
    assertThat(get(script.group(1), null).getStatusCode().value()).isEqualTo(200);
    assertThat(get("/missing.js", null).getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles() {
    var res = get("/", null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'self'");
    // default-src 'self' запрещает inline: разметка бандла не должна на него полагаться.
    String index = res.getBody();
    assertThat(index).doesNotContainPattern("(?s)<script(?![^>]*\\ssrc=)[^>]*>");
    assertThat(index).doesNotContainPattern("<style[\\s>]").doesNotContainPattern("\\sstyle=");
  }

  @Test
  void healthIsPublic() {
    assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
  }

  // --- stats ---

  @Test
  @SuppressWarnings("unchecked")
  void statsReturnsCountsAndLastUpdate() {
    when(executor.execute(eq("graph_stats_nodes"), any(), any()))
        .thenReturn(rows("graph_stats_nodes", false, Map.of("label", "Service", "count", 3L)));
    when(executor.execute(eq("graph_stats_relations"), any(), any()))
        .thenReturn(rows("graph_stats_relations", false, Map.of("type", "DEPENDS_ON", "count", 7L)));
    when(executor.execute(eq("graph_last_update"), any(), any()))
        .thenReturn(rows("graph_last_update", false, Map.of("lastUpdatedAt", "2026-01-01T00:00:00Z")));

    var res = get("/api/graph/stats", TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(res);
    assertThat(body.get("nodes").get(0).get("name").asString()).isEqualTo("Service");
    assertThat(body.get("nodes").get(0).get("count").asLong()).isEqualTo(3);
    assertThat(body.get("relations").get(0).get("count").asLong()).isEqualTo(7);
    assertThat(body.get("lastUpdatedAt").asString()).isEqualTo("2026-01-01T00:00:00Z");
  }

  // --- search ---

  @Test
  @SuppressWarnings("unchecked")
  void searchReturnsHits() {
    when(executor.execute(eq("search_assets"), any(), any()))
        .thenReturn(
            rows(
                "search_assets",
                false,
                Map.of(
                    "gid", GID, "type", "Service", "name", "Pay", "score", 1000.0, "matchType", "EXACT",
                    "sources", List.of("EAM"), "lastSeenAt", "2026-01-01T00:00:00Z")));

    var res = get("/api/graph/search?q=pay&types=Service&limit=5", TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode hit = json(res).get("items").get(0);
    assertThat(hit.get("gid").asString()).isEqualTo(GID);
    assertThat(hit.get("matchType").asString()).isEqualTo("EXACT");
    assertThat(json(res).get("truncated").asBoolean()).isFalse();
  }

  @Test
  void searchRejectsInvalidParameters() {
    String token = TestJwt.token(ADMIN);
    assertThat(get("/api/graph/search?q=%20%20", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/search", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/search?q=a&limit=0", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/search?q=a&limit=51", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/search?q=a&limit=x", token).getStatusCode().value()).isEqualTo(400);
    var badType = get("/api/graph/search?q=a&types=Evil%60)%20DETACH%20DELETE", token);
    assertThat(badType.getStatusCode().value()).isEqualTo(400);
    assertThat(badType.getBody()).doesNotContain("DETACH");
    assertThat(get("/api/graph/search?q=" + "a".repeat(201), token).getStatusCode().value()).isEqualTo(400);
    verify(executor, never()).execute(any(), any(), any());
  }

  // --- карточка ---

  @Test
  @SuppressWarnings("unchecked")
  void nodeCardHasPropertiesTimesAndSourceRecords() {
    when(executor.execute(eq("get_asset"), any(), any()))
        .thenReturn(
            rows(
                "get_asset",
                false,
                Map.of(
                    "gid", GID, "type", "Service",
                    "properties",
                        Map.of("gid", GID, "name", "Pay", "createdAt", ZonedDateTime.of(2026, 1, 1, 3, 0, 0, 0, ZoneOffset.ofHours(3))),
                    "firstSeenAt", "2026-01-01T00:00:00Z", "lastSeenAt", "2026-01-02T00:00:00Z",
                    "isCurrent", true, "sources", List.of())));
    when(executor.execute(eq("explain_provenance"), any(), any()))
        .thenReturn(
            rows(
                "explain_provenance",
                false,
                Map.of(
                    "gid", GID,
                    "records", List.of(Map.of("source", "EAM", "sourceId", "S-1", "authority", "MASTER", "active", true)))));

    var res = get("/api/graph/nodes/" + GID, TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(res);
    assertThat(body.get("isCurrent").asBoolean()).isTrue();
    assertThat(body.get("firstSeenAt").asString()).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(body.get("properties").has("gid")).isFalse();
    assertThat(body.get("properties").get("name").asString()).isEqualTo("Pay");
    assertThat(body.get("properties").get("createdAt").asString()).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(body.get("sources").get(0).get("authority").asString()).isEqualTo("MASTER");
  }

  @Test
  void nodeCardUnknownGidIs404AndBadGidIs400WithoutEcho() {
    when(executor.execute(eq("get_asset"), any(), any())).thenReturn(rows("get_asset", false));
    String token = TestJwt.token(ADMIN);

    assertThat(get("/api/graph/nodes/" + GID, token).getStatusCode().value()).isEqualTo(404);
    var bad = get("/api/graph/nodes/not-a-uuid", token);
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
    assertThat(bad.getBody()).doesNotContain("not-a-uuid");
  }

  // --- окрестность ---

  @Test
  @SuppressWarnings("unchecked")
  void neighborhoodReturnsNodesAndEdges() {
    when(executor.execute(eq("console_neighborhood_nodes"), any(), any()))
        .thenReturn(
            rows(
                "console_neighborhood_nodes",
                false,
                Map.of("gid", GID, "type", "Service", "name", "Pay", "isCurrent", true, "distance", 0L),
                Map.of("gid", "22222222-2222-2222-2222-222222222222", "type", "Team", "name", "T", "isCurrent", true, "distance", 1L)));
    when(executor.execute(eq("console_neighborhood_edges"), any(), any()))
        .thenReturn(
            rows(
                "console_neighborhood_edges",
                false,
                Map.of("type", "OWNED_BY", "source", GID, "target", "22222222-2222-2222-2222-222222222222")));

    var res = get("/api/graph/nodes/" + GID + "/neighborhood?depth=2&relTypes=OWNED_BY,DEPENDS_ON", TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    JsonNode body = json(res);
    assertThat(body.get("nodes")).hasSize(2);
    assertThat(body.get("nodes").get(1).get("distance").asInt()).isEqualTo(1);
    assertThat(body.get("edges").get(0).get("type").asString()).isEqualTo("OWNED_BY");
    assertThat(body.get("truncated").asBoolean()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void neighborhoodOverBudgetIsTruncated() {
    when(executor.execute(eq("console_neighborhood_nodes"), any(), any()))
        .thenReturn(
            rows("console_neighborhood_nodes", true,
                Map.of("gid", GID, "type", "Service", "name", "Pay", "isCurrent", true, "distance", 0L)));
    when(executor.execute(eq("console_neighborhood_edges"), any(), any()))
        .thenReturn(rows("console_neighborhood_edges", false));

    var res = get("/api/graph/nodes/" + GID + "/neighborhood", TestJwt.token(ADMIN));

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(json(res).get("truncated").asBoolean()).isTrue();
  }

  @Test
  void neighborhoodRejectsDepthAboveTwoAndUnknownRelTypes() {
    String token = TestJwt.token(ADMIN);

    assertThat(get("/api/graph/nodes/" + GID + "/neighborhood?depth=3", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/nodes/" + GID + "/neighborhood?depth=0", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/nodes/" + GID + "/neighborhood?depth=abc", token).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/graph/nodes/" + GID + "/neighborhood?relTypes=ASSERTS", token).getStatusCode().value()).isEqualTo(400);
    verify(executor, never()).execute(any(), any(), any());
  }

  // --- аудит ---

  @Test
  @SuppressWarnings("unchecked")
  void auditRecordsSubEndpointTemplatesAndNeverTheToken() {
    when(executor.execute(eq("get_asset"), any(), any()))
        .thenReturn(
            rows("get_asset", false,
                Map.of("gid", GID, "type", "Service", "properties", Map.of(), "isCurrent", true, "sources", List.of())));
    when(executor.execute(eq("explain_provenance"), any(), any()))
        .thenReturn(rows("explain_provenance", false, Map.of("gid", GID, "records", List.of())));
    String token = TestJwt.token(ADMIN);

    get("/api/graph/nodes/" + GID, token);

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.getFirst();
    Map<String, Object> kv = new java.util.HashMap<>();
    event.getKeyValuePairs().forEach(p -> kv.put(p.key, p.value));
    assertThat(kv.get("sub")).isEqualTo("test-user");
    assertThat(kv.get("endpoint")).isEqualTo("GET /api/graph/nodes/{gid}");
    assertThat(kv.get("templates")).isEqualTo(List.of("get_asset", "explain_provenance"));
    assertThat(kv.get("rows")).isEqualTo(2L);
    assertThat(kv).containsKeys("durationMs", "truncated", "status");
    assertThat(event.getFormattedMessage() + kv).doesNotContain(token).doesNotContain(GID);
  }

  @Test
  void auditDoesNotLogSearchQuery() {
    when(executor.execute(eq("search_assets"), any(), any())).thenReturn(rows("search_assets", false));

    get("/api/graph/search?q=secret-search-text", TestJwt.token(ADMIN));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getKeyValuePairs().toString()).doesNotContain("secret-search-text");
  }
}
