package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpServerInteropTest {

  @LocalServerPort int port;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private static final String TOKEN = TestJwt.token("architecture.read");

  private McpSyncClient client() {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, endpoint, body, context) -> builder.header("Authorization", "Bearer " + TOKEN))
                .build())
        .build();
  }

  @Test
  void initializeListAndCallPing() {
    try (McpSyncClient client = client()) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name()).isEqualTo("arch-rag");
      assertThat(init.capabilities().tools()).isNotNull();

      assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("ping");
      assertThat(
              client.listTools().tools().stream()
                  .filter(t -> t.name().equals("ping"))
                  .findFirst()
                  .orElseThrow()
                  .inputSchema()
                  .get("type"))
          .isEqualTo("object");

      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("ping", Map.of()));
      assertThat(result.isError()).isFalse();
      assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("\"status\":\"ok\"");
    }
  }

  @Test
  void callWithoutInitializeWorksBecauseServerIsStateless() {
    try (McpSyncClient client = client()) {
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("ping", Map.of()));
      assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("ok");
    }
  }

  @Test
  void statelessTransportAnswersWithJsonEvenIfEventStreamIsAccepted() {
    var res =
        RestClient.create("http://localhost:" + port)
            .post()
            .uri("/mcp")
            .header("Authorization", "Bearer " + TOKEN)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .body(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"ping\",\"arguments\":{}}}")
            .retrieve()
            .toEntity(String.class);
    assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    assertThat(res.getHeaders().containsHeader("Mcp-Session-Id")).isFalse();
    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var json = new JsonMapper().readTree(res.getBody());
    assertThat(json.get("jsonrpc").asString()).isEqualTo("2.0");
    assertThat(json.get("id").asInt()).isEqualTo(1);
    assertThat(json.get("result").get("isError").asBoolean()).isFalse();
    assertThat(json.get("result").get("content").get(0).get("text").asString())
        .contains("\"status\":\"ok\"");
  }

  @Test
  void legacySseEndpointIsNotServed() {
    var res =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (req, rsp) -> {})
            .build()
            .get()
            .uri("/sse")
            .header("Authorization", "Bearer " + TOKEN)
            .retrieve()
            .toBodilessEntity();
    assertThat(res.getStatusCode().value()).isEqualTo(404);
  }
}
