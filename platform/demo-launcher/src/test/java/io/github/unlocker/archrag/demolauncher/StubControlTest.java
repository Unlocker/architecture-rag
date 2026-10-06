package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
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
}
