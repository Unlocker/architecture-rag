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
import io.github.unlocker.archrag.graphquerycore.QueryTimeoutException;
import io.github.unlocker.archrag.mcpserver.audit.ToolDecision;
import io.github.unlocker.archrag.mcpserver.graph.GraphQueries;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "archrag.mcp.security.tool-scopes.probe=architecture.read")
@Import(McpAuditTest.Config.class)
class McpAuditTest {

  /** Тестовый tool: ходит в граф только через GraphQueries. */
  static class ProbeTool {
    private final GraphQueries queries;

    ProbeTool(GraphQueries queries) {
      this.queries = queries;
    }

    @McpTool(name = "probe", description = "test")
    public String probe(
        @McpToolParam(description = "q") String query,
        @McpToolParam(description = "t", required = false) String apiToken) {
      queries.execute("tpl.probe", Map.of("q", query), null);
      queries.execute("tpl.other", Map.of(), null);
      return "{}";
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    ProbeTool probeTool(GraphQueries queries) {
      return new ProbeTool(queries);
    }
  }

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

  private ResponseEntity<String> call(String token, String tool, String arguments) {
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
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool
                + "\",\"arguments\":"
                + arguments
                + "}}")
        .retrieve()
        .toEntity(String.class);
  }

  private Map<String, Object> onlyRecord() {
    assertThat(appender.list).hasSize(1);
    return appender.list.getFirst().getKeyValuePairs().stream()
        .collect(
            Collectors.toMap(
                kv -> kv.key, kv -> kv.value == null ? "null" : kv.value));
  }

  @Test
  void pingProducesOneAllowedRecordWithPrincipalAndNoToken() {
    String token = TestJwt.token("architecture.read");
    var res = call(token, "ping", "{}");

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var record = onlyRecord();
    assertThat(record).containsEntry("tool", "ping").containsEntry("decision", ToolDecision.ALLOWED);
    assertThat(record.get("principal").toString()).contains("test-user");
    assertThat(record.toString()).doesNotContain(token);
    assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain(token);
  }

  @Test
  void deniedCallsDoNotContainToken() {
    String token = TestJwt.token("other.scope");
    call(token, "ping", "{}");
    call(token, "nope", "{}");
    assertThat(appender.list).hasSize(2);
    for (var event : appender.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(token);
      assertThat(event.getKeyValuePairs().toString()).doesNotContain(token);
    }
  }

  @Test
  void unknownToolNameIsSanitizedAndTruncatedInAudit() {
    call(TestJwt.token("architecture.read"), "x\\ny" + "z".repeat(500), "{}");
    String tool = onlyRecord().get("tool").toString();
    assertThat(tool).doesNotContain("\n").hasSizeLessThanOrEqualTo(70);
  }

  @Test
  void toolFailureIsAuditedAsError() {
    when(executor.execute(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    call(TestJwt.token("architecture.read"), "probe", "{\"query\":\"abc\"}");

    var record = onlyRecord();
    assertThat(record).containsEntry("decision", ToolDecision.ERROR);
    assertThat(record.get("errorClass").toString()).endsWith("IllegalStateException");
  }

  @Test
  void insufficientScopeIsAuditedAsDeniedScope() {
    call(TestJwt.token("other.scope"), "ping", "{}");
    assertThat(onlyRecord()).containsEntry("tool", "ping").containsEntry("decision", ToolDecision.DENIED_SCOPE);
  }

  @Test
  void unknownToolIsAuditedAsDeniedUnknownTool() {
    call(TestJwt.token("architecture.read"), "nope", "{}");
    assertThat(onlyRecord())
        .containsEntry("tool", "nope")
        .containsEntry("decision", ToolDecision.DENIED_UNKNOWN_TOOL);
  }

  @Test
  void graphToolRecordsTemplatesRowsAndMasksSecretArguments() {
    when(executor.execute(eq("tpl.probe"), any(), any()))
        .thenReturn(
            new QueryResult(
                "tpl.probe", List.of(Map.of("a", 1), Map.of("a", 2)), false, 2, Duration.ZERO));
    when(executor.execute(eq("tpl.other"), any(), any()))
        .thenReturn(new QueryResult("tpl.other", List.of(Map.of("a", 3)), true, 1, Duration.ZERO));

    call(TestJwt.token("architecture.read"), "probe", "{\"query\":\"abc\",\"apiToken\":\"s3cr3t\"}");

    var record = onlyRecord();
    assertThat(record).containsEntry("decision", ToolDecision.ALLOWED).containsEntry("rows", 3L);
    assertThat(record).containsEntry("truncated", true);
    assertThat(record.get("templates")).isEqualTo(List.of("tpl.probe", "tpl.other"));
    assertThat(record.get("arguments").toString()).contains("abc").doesNotContain("s3cr3t");
  }

  @Test
  void queryTimeoutIsAuditedAsTimeout() {
    when(executor.execute(any(), any(), any())).thenThrow(new QueryTimeoutException("tpl.probe", null));

    call(TestJwt.token("architecture.read"), "probe", "{\"query\":\"abc\"}");

    var record = onlyRecord();
    assertThat(record).containsEntry("decision", ToolDecision.TIMEOUT);
    assertThat(record.get("errorClass").toString()).endsWith("QueryTimeoutException");
  }
}
