package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
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

class EamApiControlTest {

  private final HttpClient http = HttpClient.newHttpClient();
  private EamApiStub stub;
  private EamApiControl control;

  @BeforeEach
  void start() throws Exception {
    var store = EamApiSeed.seeded(Clock.systemUTC());
    stub = new EamApiStub(store, "tok");
    control = new EamApiControl(store, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
  }

  @AfterEach
  void stop() {
    control.close();
    stub.close();
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + control.port() + path))
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> api(String method, String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(stub.baseUri() + path)).header("Authorization", "Token tok")
        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void upsertAppearsInApiListAndBumpsVer() throws Exception {
    var created = post("/control/upsert", "{\"type\":\"itsystems\",\"id\":5999,\"attrs\":{\"name\":\"Probe\"}}");
    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(created.body()).contains("\"ver\":1");
    assertThat(api("GET", "/api/itsystems/").body()).contains("Probe");
    var updated = post("/control/upsert", "{\"type\":\"itsystems\",\"id\":5999,\"attrs\":{\"name\":\"Probe 2\"}}");
    assertThat(updated.body()).contains("\"ver\":2");
  }

  @Test
  void archiveRemovesObjectFromMainList() throws Exception {
    assertThat(post("/control/archive", "{\"type\":\"platforms\",\"id\":3102}").statusCode()).isEqualTo(200);
    assertThat(api("GET", "/api/platforms/3102/").statusCode()).isEqualTo(404);
    assertThat(api("GET", "/api/platforms/").body()).doesNotContain("PostgreSQL Platform");
  }

  @Test
  void invalidRequestsAreRejectedWithoutEcho() throws Exception {
    assertThat(post("/control/upsert", "not json SECRET").body()).doesNotContain("SECRET");
    assertThat(post("/control/upsert", "not json").statusCode()).isEqualTo(400);
    assertThat(post("/control/upsert", "{\"type\":\"buffers\",\"id\":1,\"attrs\":{}}").statusCode()).isEqualTo(400);
    assertThat(post("/control/upsert", "{\"type\":\"itsystems\",\"id\":\"x\",\"attrs\":{}}").statusCode()).isEqualTo(400);
    assertThat(post("/control/upsert", "{\"type\":\"itsystems\",\"id\":1}").statusCode()).isEqualTo(400);
    var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + control.port() + "/control/upsert")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }

  @Test
  void apiStaysReadOnly() throws Exception {
    assertThat(api("POST", "/api/itsystems/").statusCode()).isEqualTo(405);
    assertThat(api("DELETE", "/api/itsystems/5101/").statusCode()).isEqualTo(405);
  }
}
