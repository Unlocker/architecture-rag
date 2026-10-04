package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpServerInteropTest {

  @LocalServerPort int port;

  private McpSyncClient client() {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
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

      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("ping", Map.of("message", "hi")));
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
        org.springframework.web.client.RestClient.create("http://localhost:" + port)
            .post()
            .uri("/mcp")
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .body(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"ping\",\"arguments\":{}}}")
            .retrieve()
            .toEntity(String.class);
    assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    assertThat(res.getHeaders().containsHeader("Mcp-Session-Id")).isFalse();
    assertThat(res.getBody()).contains("status");
  }

  @Test
  void legacySseEndpointIsNotServed() {
    var res =
        org.springframework.web.client.RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (req, rsp) -> {})
            .build()
            .get()
            .uri("/sse")
            .retrieve()
            .toBodilessEntity();
    assertThat(res.getStatusCode().value()).isEqualTo(404);
  }
}
