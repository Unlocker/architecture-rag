package io.github.unlocker.archrag.sourcestubs;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcespi.WebhookSignature;
import io.github.unlocker.archrag.sourcespi.WebhookSignature.Result;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubWebhookSenderTest {

  private static final String SECRET = "test-secret";
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
  private final List<Result> verdicts = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private StubWebhookSender sender;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/hook", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Result r = WebhookSignature.verify(
          SECRET,
          ex.getRequestHeaders().getFirst(WebhookSignature.TIMESTAMP_HEADER),
          ex.getRequestHeaders().getFirst(WebhookSignature.SIGNATURE_HEADER),
          body, clock.instant(), Duration.ofMinutes(5));
      verdicts.add(r);
      bodies.add(body);
      ex.sendResponseHeaders(r == Result.VALID ? 202 : 401, -1);
      ex.close();
    });
    server.start();
    sender = new StubWebhookSender(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"), SECRET, clock);
  }

  @AfterEach
  void stop() {
    sender.close();
    server.stop(0);
  }

  private WebhookEvent event() {
    return StubSources.eam(clock).webhookEvents().getLast();
  }

  @Test
  void signedWebhookIsAcceptedAndCarriesOnlyNotification() {
    assertThat(sender.send(event())).isEqualTo(202);
    assertThat(verdicts).containsExactly(Result.VALID);
    assertThat(bodies.getFirst())
        .contains("\"eventId\":\"eam-evt-3\"", "\"sourceId\":\"EAM-1042\"", "\"sourceVersion\":1",
            "\"operation\":\"UPSERT\"")
        .doesNotContain("Payments Core");
  }

  @Test
  void staleTimestampIsRejectedByReplayWindow() {
    assertThat(sender.sendAt(event(), clock.instant().minus(Duration.ofHours(1)))).isEqualTo(401);
    assertThat(verdicts).containsExactly(Result.OUT_OF_WINDOW);
  }

  @Test
  void wrongSignatureIsRejected() {
    assertThat(sender.sendWithBadSignature(event())).isEqualTo(401);
    assertThat(verdicts).containsExactly(Result.BAD_SIGNATURE);
  }

  @Test
  void duplicateDeliveryKeepsEventId() {
    WebhookEvent e = event();
    sender.send(e);
    sender.send(e);
    assertThat(bodies).hasSize(2).containsOnly(bodies.getFirst());
  }
}
