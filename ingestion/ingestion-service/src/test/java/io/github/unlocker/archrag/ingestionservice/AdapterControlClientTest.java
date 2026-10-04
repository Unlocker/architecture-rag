package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Прокси к adapter-сервису: 202 и 409 как есть, прочее и недоступность — {@link AdapterUnavailableException}. */
class AdapterControlClientTest {

  private static HttpServer adapter(int status, String body) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    s.createContext("/control/snapshot", ex -> {
      byte[] b = body.getBytes();
      ex.sendResponseHeaders("POST".equals(ex.getRequestMethod()) ? status : 405, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    s.start();
    return s;
  }

  private static AdapterControlClient client(HttpServer s) {
    String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/control/snapshot";
    return new AdapterControlClient(new AdaptersProperties(Map.of("eam", new AdaptersProperties.Adapter(url))));
  }

  @Test
  void acceptedAndAlreadyRunningArePassedThrough() throws Exception {
    HttpServer ok = adapter(202, "{\"outcome\":\"SNAPSHOT_COMPLETED\"}");
    HttpServer busy = adapter(409, "{\"outcome\":\"ALREADY_RUNNING\"}");
    try {
      assertThat(client(ok).snapshot("eam")).isEqualTo(new AdapterControlClient.Response(202, "{\"outcome\":\"SNAPSHOT_COMPLETED\"}"));
      assertThat(client(busy).snapshot("urn:corp:eam").status()).isEqualTo(409);
    } finally {
      ok.stop(0);
      busy.stop(0);
    }
  }

  @Test
  void otherStatusAndConnectionFailureAreUnavailable() throws Exception {
    HttpServer broken = adapter(503, "{}");
    var client = client(broken);
    broken.stop(0);
    assertThatThrownBy(() -> client.snapshot("eam")).isInstanceOf(AdapterUnavailableException.class);

    HttpServer unavailable = adapter(503, "{}");
    try {
      assertThatThrownBy(() -> client(unavailable).snapshot("eam")).isInstanceOf(AdapterUnavailableException.class);
    } finally {
      unavailable.stop(0);
    }
  }

  @Test
  void unknownSourceIsRejected() {
    var client = new AdapterControlClient(new AdaptersProperties(null));
    assertThat(client.knows("eam")).isFalse();
    assertThatThrownBy(() -> client.snapshot("eam")).isInstanceOf(IllegalArgumentException.class);
  }
}
