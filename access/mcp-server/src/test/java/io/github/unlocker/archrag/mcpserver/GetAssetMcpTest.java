package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

/** get_asset через Streamable HTTP: scope, аудит и вид ошибки аргумента у клиента. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GetAssetMcpTest {

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
    ((Logger) LoggerFactory.getLogger("archrag.audit.mcp")).addAppender(appender);
  }

  @AfterEach
  void release() {
    ((Logger) LoggerFactory.getLogger("archrag.audit.mcp")).detachAppender(appender);
  }

  private ResponseEntity<String> call(String token, String arguments) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .post()
        .uri("/mcp")
        .header("Accept", "application/json, text/event-stream")
        .header("Content-Type", "application/json")
        .headers(h -> h.setBearerAuth(token))
        .body(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_asset\","
                + "\"arguments\":"
                + arguments
                + "}}")
        .retrieve()
        .toEntity(String.class);
  }

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  @Test
  void callWithScopeReturnsCardAndAuditsBothTemplates() {
    var card = new java.util.HashMap<String, Object>();
    card.put("gid", GID);
    card.put("type", "Service");
    card.put("properties", Map.of("name", "Pay"));
    card.put("firstSeenAt", "2026-01-01T00:00:00Z");
    card.put("lastSeenAt", "2026-01-02T00:00:00Z");
    card.put("deletedAt", null);
    card.put("isCurrent", true);
    card.put(
        "sources",
        List.of(
            Map.of(
                "source", "EAM", "sourceType", "component", "sourceId", "EAM-1", "sourceVersion", "1",
                "fetchedAt", "2026-01-01T00:00:00Z", "active", true, "authority", "MASTER")));
    when(executor.execute(eq("get_asset"), any(), any()))
        .thenReturn(new QueryResult("get_asset", List.of(card), false, 1, Duration.ZERO));
    when(executor.execute(eq("asset_relations"), any(), any()))
        .thenReturn(
            new QueryResult(
                "asset_relations",
                List.of(
                    Map.of(
                        "relationType", "HAS_DEPLOYMENT", "direction", "OUT", "gid", "g2", "type", "Deployment",
                        "name", "dep", "isCurrent", true, "validFrom", "2026-01-01T00:00:00Z")),
                false,
                1,
                Duration.ZERO));

    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"" + GID + "\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var result = new JsonMapper().readTree(res.getBody()).get("result");
    assertThat(result.get("isError").asBoolean()).isFalse();
    var payload = new JsonMapper().readTree(result.get("content").get(0).get("text").asString());
    assertThat(payload.get("gid").asString()).isEqualTo(GID);
    assertThat(payload.get("type").asString()).isEqualTo("Service");
    assertThat(payload.get("isCurrent").asBoolean()).isTrue();
    assertThat(payload.get("lastSeenAt").isString()).isTrue();
    assertThat(java.time.Instant.parse(payload.get("lastSeenAt").asString())).isEqualTo("2026-01-02T00:00:00Z");
    assertThat(payload.get("sources").get(0).get("authority").asString()).isEqualTo("MASTER");
    assertThat(payload.get("relations").get(0).get("gid").asString()).isEqualTo("g2");
    assertThat(payload.get("truncated").asBoolean()).isFalse();
    assertThat(appender.list).hasSize(1);
    var kv = appender.list.getFirst().getKeyValuePairs().stream().filter(p -> p.key.equals("templates")).findFirst();
    assertThat(kv).isPresent();
    assertThat(kv.get().value).isEqualTo(List.of("get_asset", "asset_relations"));
  }

  @Test
  void callWithoutRequiredScopeIsDenied() {
    var res = call(TestJwt.token("other.scope"), "{\"gid\":\"" + GID + "\"}");

    assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(res.getBody() == null ? "" : res.getBody()).doesNotContain(GID);
  }

  @Test
  void invalidGidIsReportedAsToolErrorWithoutEchoingInput() {
    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"leaky-value\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("\"isError\":true").contains("gid must be a UUID").doesNotContain("leaky-value");
  }

  @Test
  void unknownGidIsReportedAsToolErrorWithoutGid() {
    when(executor.execute(eq("get_asset"), any(), any()))
        .thenReturn(new QueryResult("get_asset", List.of(), false, 0, Duration.ZERO));

    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"" + GID + "\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("\"isError\":true").contains("asset not found").doesNotContain(GID);
  }
}
