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

/** search_assets через Streamable HTTP: scope, аудит и вид ошибки аргумента у клиента. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SearchAssetsMcpTest {

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
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_assets\","
                + "\"arguments\":"
                + arguments
                + "}}")
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void callWithScopeReturnsHitsAndAuditsTemplateId() {
    when(executor.execute(eq("search_assets"), any(), any()))
        .thenReturn(
            new QueryResult(
                "search_assets",
                List.of(
                    Map.of(
                        "gid", "g1", "type", "Service", "name", "Pay", "score", 1000.0,
                        "matchType", "EXACT", "sources", List.of("EAM"),
                        "lastSeenAt", "2026-01-01T00:00:00Z")),
                false,
                1,
                Duration.ZERO));

    var res = call(TestJwt.token("architecture.read"), "{\"query\":\"g1\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var result = new JsonMapper().readTree(res.getBody()).get("result");
    assertThat(result.get("isError").asBoolean()).isFalse();
    // Ответ tool — JSON в text-контенте: проверяем форму полей, как их увидит агент (критерий приёмки 5).
    var payload = new JsonMapper().readTree(result.get("content").get(0).get("text").asString());
    assertThat(payload.get("truncated").asBoolean()).isFalse();
    var hit = payload.get("items").get(0);
    assertThat(hit.get("gid").asString()).isEqualTo("g1");
    assertThat(hit.get("type").asString()).isEqualTo("Service");
    assertThat(hit.get("name").asString()).isEqualTo("Pay");
    assertThat(hit.get("score").asDouble()).isEqualTo(1000.0);
    assertThat(hit.get("matchType").asString()).isEqualTo("EXACT");
    assertThat(hit.get("sources").get(0).asString()).isEqualTo("EAM");
    assertThat(hit.get("lastSeenAt").isString()).isTrue();
    assertThat(java.time.Instant.parse(hit.get("lastSeenAt").asString())).isEqualTo("2026-01-01T00:00:00Z");
    assertThat(appender.list).hasSize(1);
    var kv = appender.list.getFirst().getKeyValuePairs().stream().filter(p -> p.key.equals("templates")).findFirst();
    assertThat(kv).isPresent();
    assertThat(kv.get().value).isEqualTo(List.of("search_assets"));
  }

  @Test
  void callWithoutRequiredScopeIsDenied() {
    var res = call(TestJwt.token("other.scope"), "{\"query\":\"g1\"}");

    assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(res.getBody() == null ? "" : res.getBody()).doesNotContain("g1\"");
  }

  @Test
  void invalidArgumentIsReportedAsToolErrorWithoutEchoingInput() {
    var res = call(TestJwt.token("architecture.read"), "{\"query\":\"abc\",\"limit\":0}");

    // Фактический вид ошибки у клиента: HTTP 200, result.isError=true и текст исключения в content.
    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("\"isError\":true").contains("limit must be in 1..50");
  }
}
