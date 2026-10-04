package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** {@code POST /control/snapshot}: 202 / 409 / 503, чужие методы и отсутствие контекста без запуска snapshot. */
class WebhookServerControlTest {

  private static WebhookServer server(Supplier<PollResult> snapshot) {
    var config = AdapterConfig.of(SourceSystem.EAM, URI.create("http://unused"), "secret-value");
    var handler = new WebhookHandler(config, StubSources.eam(Clock.systemUTC()), new InMemoryJournal(),
        new InMemoryRawStore(), Clock.systemUTC());
    return new WebhookServer(handler, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), snapshot);
  }

  private static HttpResponse<String> call(WebhookServer s, String method) throws Exception {
    URI u = s.endpoint().resolve("/control/snapshot");
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(u).method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void completedSnapshotIs202WithOutcome() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      return new PollResult(PollResult.Outcome.SNAPSHOT_COMPLETED, 5, "run-1");
    })) {
      var r = call(s, "POST");
      assertThat(r.statusCode()).isEqualTo(202);
      assertThat(r.body()).contains("SNAPSHOT_COMPLETED").contains("run-1");
      assertThat(calls[0]).isEqualTo(1);
    }
  }

  @Test
  void alreadyRunningIs409AndUnavailableSourceIs503() throws Exception {
    try (var s = server(() -> new PollResult(PollResult.Outcome.ALREADY_RUNNING, 0, null))) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(409);
    }
    try (var s = server(() -> new PollResult(PollResult.Outcome.GAVE_UP, 0, "run-2"))) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(503);
    }
  }

  @Test
  void failureIs500WithoutDetailsAndGetIsRejectedWithoutRunning() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      throw new IllegalStateException("secret detail");
    })) {
      var r = call(s, "POST");
      assertThat(r.statusCode()).isEqualTo(500);
      assertThat(r.body()).doesNotContain("secret detail");
      assertThat(call(s, "GET").statusCode()).isEqualTo(405);
      assertThat(calls[0]).isEqualTo(1);
    }
  }

  @Test
  void noControlContextWithoutSnapshotSupplier() throws Exception {
    try (var s = server(null)) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(404);
    }
  }
}
