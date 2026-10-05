package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.time.Duration;
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
import tools.jackson.databind.json.JsonMapper;

/** trace_dependencies через Streamable HTTP: scope, ответ и вид ошибки аргумента у клиента. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TraceDependenciesMcpTest {

  private static final String GID = "11111111-1111-1111-1111-111111111111";

  @LocalServerPort int port;
  @MockitoBean GraphQueryExecutor executor;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private ResponseEntity<String> post(String token, String body) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .post()
        .uri("/mcp")
        .header("Accept", "application/json, text/event-stream")
        .header("Content-Type", "application/json")
        .headers(h -> h.setBearerAuth(token))
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> call(String token, String arguments) {
    return post(
        token,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"trace_dependencies\","
            + "\"arguments\":" + arguments + "}}");
  }

  @Test
  void toolIsListed() {
    var res = post(TestJwt.token(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("trace_dependencies").contains("maxDepth").contains("relationTypes");
  }

  @Test
  void callWithScopeReturnsPathsAndTruncated() {
    var a = Map.<String, Object>of("gid", GID, "label", "ITSystem", "name", "Pay", "lastSeenAt", "2026-01-01T00:00:00Z");
    var b = Map.<String, Object>of("gid", "g2", "label", "Service", "name", "Billing", "lastSeenAt", "2026-01-01T00:00:00Z");
    var r = Map.<String, Object>of("type", "DECOMPOSED_INTO", "from", GID, "to", "g2", "validFrom", "2026-01-01T00:00:00Z",
        "assertedBySource", "EAM", "assertedByType", "relation", "assertedById", "x");
    when(executor.execute(eq("trace_downstream"), any(), any()))
        .thenReturn(new QueryResult("trace_downstream", List.of(Map.of("nodes", List.of(a, b), "relations", List.of(r))),
            true, 1, Duration.ZERO));

    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"" + GID + "\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var result = new JsonMapper().readTree(res.getBody()).get("result");
    assertThat(result.get("isError").asBoolean()).isFalse();
    var payload = new JsonMapper().readTree(result.get("content").get(0).get("text").asString());
    assertThat(payload.get("direction").asString()).isEqualTo("downstream");
    assertThat(payload.get("nodes")).hasSize(2);
    assertThat(payload.get("paths").get(0).get("nodes").get(1).asString()).isEqualTo("g2");
    assertThat(payload.get("truncated").asBoolean()).isTrue();
  }

  @Test
  void callWithoutRequiredScopeIsDenied() {
    var res = call(TestJwt.token("other.scope"), "{\"gid\":\"" + GID + "\"}");

    assertThat(res.getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void invalidInputIsReportedAsToolErrorWithoutEchoingInput() {
    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"" + GID + "\",\"relationTypes\":[\"leaky-value\"]}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("\"isError\":true").contains("Unknown relation type").doesNotContain("leaky-value");
  }

  @Test
  void impactModeIsReportedAsToolError() {
    var res = call(TestJwt.token("architecture.read"), "{\"gid\":\"" + GID + "\",\"mode\":\"impact\"}");

    assertThat(res.getBody()).contains("\"isError\":true").contains("mode impact is not supported yet");
  }
}
