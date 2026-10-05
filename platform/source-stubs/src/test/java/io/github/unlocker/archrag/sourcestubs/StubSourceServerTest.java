package io.github.unlocker.archrag.sourcestubs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubSourceServerTest {

  private static final Pattern NEXT_CURSOR = Pattern.compile("\"nextCursor\":\"([^\"]*)\"");
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
  private final HttpClient http = HttpClient.newHttpClient();
  private StubSource source;
  private StubSourceServer server;

  @BeforeEach
  void start() {
    source = StubSources.eam(clock);
    server = new StubSourceServer(source);
  }

  @AfterEach
  void stop() {
    server.close();
    http.close();
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(server.baseUri() + path)).GET().build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String cursorOf(String body) {
    Matcher m = NEXT_CURSOR.matcher(body);
    assertThat(m.find()).isTrue();
    return URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
  }

  @Test
  void explicitAddressOverloadServesOnChosenPort() throws Exception {
    try (var other = new StubSourceServer(source,
        new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))) {
      assertThat(other.port()).isPositive();
      HttpRequest request = HttpRequest.newBuilder(URI.create(other.baseUri() + "/changes?limit=1")).GET().build();
      assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
  }

  @Test
  void pagesAreWalkedByCursor() throws Exception {
    HttpResponse<String> first = get("/changes?limit=2");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"hasMore\":true", "\"snapshotComplete\":false");

    HttpResponse<String> second = get("/changes?limit=2&cursor=" + cursorOf(first.body()));
    assertThat(second.body())
        .contains("\"hasMore\":false", "\"snapshotComplete\":true", "\"sourceId\":\"EAM-1042\"");
    assertThat(second.body()).doesNotContain("\"sourceId\":\"TEAM-PAY\"");
  }

  @Test
  void objectSnapshotAndNotFound() throws Exception {
    HttpResponse<String> found = get("/objects/IT_SYSTEM/EAM-1042");
    assertThat(found.statusCode()).isEqualTo(200);
    assertThat(found.body()).contains("\"sourceVersion\":1", "\"name\":\"Payments Core\"");
    assertThat(get("/objects/IT_SYSTEM/nope").statusCode()).isEqualTo(404);
  }

  @Test
  void invalidLimitIsBadRequest() throws Exception {
    assertThat(get("/changes?limit=0").statusCode()).isEqualTo(400);
    assertThat(get("/changes?limit=abc").statusCode()).isEqualTo(400);
  }

  @Test
  void rateLimitCarriesRetryAfterOnScheduledCall() throws Exception {
    server.failOnCall(2, 429, Duration.ofSeconds(7));
    assertThat(get("/changes").statusCode()).isEqualTo(200);
    HttpResponse<String> limited = get("/changes");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).contains("7");
    assertThat(get("/changes").statusCode()).isEqualTo(200);
  }

  @Test
  void serverErrorFromSourceFailureIsPropagated() throws Exception {
    source.failNext(1, 503, null);
    HttpResponse<String> failed = get("/objects/IT_SYSTEM/EAM-1042");
    assertThat(failed.statusCode()).isEqualTo(503);
    assertThat(failed.headers().firstValue("Retry-After")).isEmpty();
    assertThat(get("/objects/IT_SYSTEM/EAM-1042").statusCode()).isEqualTo(200);
  }

  @Test
  void timeoutIsReportedAsGatewayTimeout() throws Exception {
    server.failOnCall(1, 0, null);
    assertThat(get("/changes").statusCode()).isEqualTo(504);
  }

  @Test
  void writesAreRejected() throws Exception {
    HttpRequest post = HttpRequest.newBuilder(URI.create(server.baseUri() + "/changes"))
        .POST(HttpRequest.BodyPublishers.noBody()).build();
    assertThat(http.send(post, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(405);
  }
}
