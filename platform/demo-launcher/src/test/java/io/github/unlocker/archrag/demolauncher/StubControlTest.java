package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.WebhookSignature;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubWebhookSender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubControlTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private StubSource source;
  private StubSourceServer server;
  private StubControl control;

  @BeforeEach
  void start() throws Exception {
    source = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    server = new StubSourceServer(source);
    control = new StubControl(source, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
  }

  @AfterEach
  void stop() {
    control.close();
    server.close();
  }

  private HttpResponse<String> post(String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + control.port() + "/control/upsert"))
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private String changes() throws Exception {
    return http.send(HttpRequest.newBuilder(server.baseUri().resolve("/changes?limit=100")).build(),
        HttpResponse.BodyHandlers.ofString()).body();
  }

  @Test
  void upsertAppearsInChanges() throws Exception {
    var response = post("{\"type\":\"IT_SYSTEM\",\"id\":\"EAM-9\",\"payload\":{\"name\":\"Backup Probe\"}}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("sourceVersion");
    assertThat(changes()).contains("EAM-9").contains("Backup Probe");
  }

  @Test
  void invalidBodyIsRejectedWithoutChangingSource() throws Exception {
    String before = changes();
    for (String bad : new String[] {"not json", "{}", "{\"type\":\"T\",\"id\":\"\",\"payload\":{}}",
        "{\"type\":\"T\",\"id\":\"x\",\"payload\":[1]}"}) {
      assertThat(post(bad).statusCode()).as(bad).isEqualTo(400);
    }
    assertThat(changes()).isEqualTo(before);
  }

  @Test
  void oversizedBodyIsRejected() throws Exception {
    String big = "{\"type\":\"T\",\"id\":\"x\",\"payload\":{\"a\":\"" + "x".repeat(StubControl.MAX_BODY_BYTES) + "\"}}";
    assertThat(post(big).statusCode()).isEqualTo(400);
  }

  @Test
  void getIsNotAllowed() throws Exception {
    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + control.port() + "/control/upsert"))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(405);
  }

  @Test
  void controlIsNotServedOnSourcePort() throws Exception {
    var response = http.send(HttpRequest.newBuilder(server.baseUri().resolve("/control/upsert"))
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
  }

  private record Received(String eventId, String timestamp, String signature, String body) {}

  private HttpServer receiver(List<Received> received, int status) throws Exception {
    HttpServer rx = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    rx.createContext("/webhook", ex -> {
      try (ex) {
        var h = ex.getRequestHeaders();
        received.add(new Received(h.getFirst(WebhookSignature.EVENT_ID_HEADER),
            h.getFirst(WebhookSignature.TIMESTAMP_HEADER), h.getFirst(WebhookSignature.SIGNATURE_HEADER),
            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        ex.sendResponseHeaders(status, -1);
      }
    });
    rx.start();
    return rx;
  }

  private StubControl controlWithWebhook(URI target) throws Exception {
    return new StubControl(source, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        new StubWebhookSender(target, "s3cret", Clock.systemUTC()));
  }

  private HttpResponse<String> post(StubControl c, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + c.port() + "/control/upsert"))
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static final String UPSERT = "{\"type\":\"IT_SYSTEM\",\"id\":\"EAM-9\",\"payload\":{\"name\":\"Probe\"}}";

  @Test
  void withTargetUpsertSendsSignedWebhook() throws Exception {
    var received = new CopyOnWriteArrayList<Received>();
    HttpServer rx = receiver(received, 202);
    try (StubControl c = controlWithWebhook(URI.create("http://127.0.0.1:" + rx.getAddress().getPort() + "/webhook"))) {
      var response = post(c, UPSERT);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"webhookStatus\":202");
      assertThat(received).hasSize(1);
      Received r = received.get(0);
      assertThat(r.body()).contains("\"sourceId\":\"EAM-9\"").contains("\"sourceVersion\":1");
      assertThat(r.eventId()).isEqualTo(source.webhookEvents().getLast().eventId());
      assertThat(r.signature()).isEqualTo(WebhookSignature.sign("s3cret", Long.parseLong(r.timestamp()), r.body()));
    } finally {
      rx.stop(0);
    }
  }

  @Test
  void notifyFalseSkipsWebhook() throws Exception {
    var received = new CopyOnWriteArrayList<Received>();
    HttpServer rx = receiver(received, 202);
    try (StubControl c = controlWithWebhook(URI.create("http://127.0.0.1:" + rx.getAddress().getPort() + "/webhook"))) {
      var response = post(c, "{\"type\":\"IT_SYSTEM\",\"id\":\"EAM-9\",\"notify\":false,\"payload\":{\"name\":\"Probe\"}}");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).doesNotContain("webhookStatus");
      assertThat(received).isEmpty();
      assertThat(changes()).contains("EAM-9");
    } finally {
      rx.stop(0);
    }
  }

  @Test
  void unreachableReceiverStillSucceedsWithFailedStatus() throws Exception {
    HttpServer rx = receiver(new CopyOnWriteArrayList<>(), 202);
    URI target = URI.create("http://127.0.0.1:" + rx.getAddress().getPort() + "/webhook");
    rx.stop(0);
    try (StubControl c = controlWithWebhook(target)) {
      var response = post(c, UPSERT);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"webhookStatus\":0").contains("sourceVersion");
      assertThat(changes()).contains("EAM-9");
    }
  }

  @Test
  void receiverErrorStatusIsReportedNotThrown() throws Exception {
    HttpServer rx = receiver(new CopyOnWriteArrayList<>(), 401);
    try (StubControl c = controlWithWebhook(URI.create("http://127.0.0.1:" + rx.getAddress().getPort() + "/webhook"))) {
      var response = post(c, UPSERT);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"webhookStatus\":401");
    } finally {
      rx.stop(0);
    }
  }

  @Test
  void withoutTargetResponseHasNoWebhookStatus() throws Exception {
    assertThat(post(UPSERT).body()).doesNotContain("webhookStatus");
  }
}
