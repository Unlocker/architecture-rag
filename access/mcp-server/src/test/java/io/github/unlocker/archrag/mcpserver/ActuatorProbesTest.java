package io.github.unlocker.archrag.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ActuatorProbesTest {

  @LocalServerPort int port;

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build();
  }

  @Test
  void livenessAndReadinessAreUp() {
    for (String probe : new String[] {"liveness", "readiness"}) {
      var res = http().get().uri("/actuator/health/" + probe).retrieve().toEntity(String.class);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }
  }

  @Test
  void envEndpointIsNotExposed() {
    var res = http().get().uri("/actuator/env").retrieve().toEntity(String.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
