package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "archrag.mcp.security.max-request-bytes=2KB")
class McpSecurityTest {

  private static final String CALL_PING =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
          + "\"params\":{\"name\":\"ping\",\"arguments\":{}}}";

  @LocalServerPort int port;

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
        .headers(
            h -> {
              if (token != null) h.setBearerAuth(token);
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private static String challenge(ResponseEntity<String> res) {
    return String.valueOf(res.getHeaders().getFirst("WWW-Authenticate"));
  }

  @Test
  void requestWithoutTokenIsUnauthorizedWithMetadataPointer() {
    var res = post(null, CALL_PING);
    assertThat(res.getStatusCode().value()).isEqualTo(401);
    assertThat(challenge(res)).contains("Bearer").contains("resource_metadata=");
  }

  @Test
  void foreignAudienceIssuerAndExpiredTokensAreUnauthorized() {
    var now = Instant.now();
    var scopes = List.of("architecture.read");
    assertThat(
            post(
                    TestJwt.token(
                        scopes, "https://other/mcp", TestJwt.ISSUER, now.plusSeconds(300)),
                    CALL_PING)
                .getStatusCode()
                .value())
        .isEqualTo(401);
    assertThat(
            post(
                    TestJwt.token(
                        scopes, TestJwt.RESOURCE, "https://evil/realms/x", now.plusSeconds(300)),
                    CALL_PING)
                .getStatusCode()
                .value())
        .isEqualTo(401);
    assertThat(
            post(
                    TestJwt.token(scopes, TestJwt.RESOURCE, TestJwt.ISSUER, now.minusSeconds(3600)),
                    CALL_PING)
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  void tokenWithoutScopeCanInitializeAndListTools() {
    String token = TestJwt.token();
    var init =
        post(
            token,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
    assertThat(init.getStatusCode().value()).isEqualTo(200);
    var list = post(token, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    assertThat(list.getStatusCode().value()).isEqualTo(200);
    assertThat(list.getBody()).contains("ping");
  }

  @Test
  void toolCallWithoutScopeIsForbiddenWithScopeChallenge() {
    var res = post(TestJwt.token(), CALL_PING);
    assertThat(res.getStatusCode().value()).isEqualTo(403);
    assertThat(challenge(res))
        .contains("error=\"insufficient_scope\"")
        .contains("scope=\"architecture.read\"")
        .contains("resource_metadata=");
    assertThat(res.getBody()).isNullOrEmpty();
  }

  @Test
  void toolCallWithOtherScopeIsForbidden() {
    var res = post(TestJwt.token("other.scope"), CALL_PING);
    assertThat(res.getStatusCode().value()).isEqualTo(403);
    assertThat(challenge(res)).contains("scope=\"architecture.read\"");
  }

  @Test
  void unknownCharsetIsBadRequest() throws Exception {
    // RestClient сам отвергает неизвестную кодировку в Content-Type, поэтому — java.net.http.
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json;charset=bogus")
            .header("Authorization", "Bearer " + TestJwt.token("architecture.read"))
            .POST(HttpRequest.BodyPublishers.ofString(CALL_PING))
            .build();
    var res = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(400);
  }

  @Test
  void toolCallWithScopeSucceeds() {
    var res = post(TestJwt.token("architecture.read"), CALL_PING);
    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getBody()).contains("status").contains("ok");
  }

  @Test
  void unmappedToolIsForbiddenEvenWithScope() {
    var res =
        post(
            TestJwt.token("architecture.read"),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
    assertThat(res.getStatusCode().value()).isEqualTo(403);
    assertThat(challenge(res)).contains("insufficient_scope").doesNotContain("scope=\"");
  }

  @Test
  void batchAndMalformedBodiesAreBadRequest() {
    String token = TestJwt.token("architecture.read");
    assertThat(post(token, "[" + CALL_PING + "]").getStatusCode().value()).isEqualTo(400);
    assertThat(post(token, "{not json").getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void oversizedBodyIsRejected() {
    var res = post(TestJwt.token("architecture.read"), "{\"pad\":\"" + "x".repeat(4096) + "\"}");
    assertThat(res.getStatusCode().value()).isEqualTo(413);
  }

  @Test
  void protectedResourceMetadataIsPublic() {
    var res =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (req, rsp) -> {})
            .build()
            .get()
            .uri("/.well-known/oauth-protected-resource")
            .retrieve()
            .toEntity(String.class);
    assertThat(res.getStatusCode().value()).isEqualTo(200);
    var json = new JsonMapper().readTree(res.getBody());
    assertThat(json.get("resource").asString()).isEqualTo(TestJwt.RESOURCE);
    assertThat(json.get("authorization_servers").toString()).contains(TestJwt.ISSUER);
    assertThat(json.get("scopes_supported").toString()).contains("architecture.read");
    assertThat(json.get("bearer_methods_supported").toString()).isEqualTo("[\"header\"]");
  }
}
