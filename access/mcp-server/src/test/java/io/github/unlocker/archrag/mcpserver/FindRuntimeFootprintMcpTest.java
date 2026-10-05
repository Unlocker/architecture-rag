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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FindRuntimeFootprintMcpTest {

  private static final String TOOL = "find_runtime_footprint";

  @LocalServerPort int port;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private McpSyncClient client(String token) {
    return McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer(
                    (builder, method, endpoint, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build())
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolIsListedWithSystemGidAndOptionalEnvironment() {
    try (McpSyncClient client = client(TestJwt.token("architecture.read"))) {
      client.initialize();
      McpSchema.Tool tool =
          client.listTools().tools().stream()
              .filter(t -> t.name().equals(TOOL))
              .findFirst()
              .orElseThrow();
      assertThat((Map<String, Object>) tool.inputSchema().get("properties"))
          .containsKeys("systemGid", "environment");
      assertThat((java.util.List<String>) tool.inputSchema().get("required")).containsExactly("systemGid");
    }
  }

  @Test
  void invalidSystemGidIsToolErrorWithoutEchoingInput() {
    try (McpSyncClient client = client(TestJwt.token("architecture.read"))) {
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest(TOOL, Map.of("systemGid", "SECRET-not-uuid")));
      assertThat(result.isError()).isTrue();
      String text = ((McpSchema.TextContent) result.content().get(0)).text();
      assertThat(text).doesNotContain("SECRET");
    }
  }

  @Test
  void callWithoutScopeIsForbidden() {
    var res =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (req, rsp) -> {})
            .build()
            .post()
            .uri("/mcp")
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .headers(h -> h.setBearerAuth(TestJwt.token()))
            .body(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                    + TOOL
                    + "\",\"arguments\":{\"systemGid\":\"11111111-1111-1111-1111-111111111111\"}}}")
            .retrieve()
            .toEntity(String.class);
    assertThat(res.getStatusCode().value()).isEqualTo(403);
  }
}
