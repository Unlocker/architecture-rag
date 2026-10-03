package io.github.unlocker.akp.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphProjectorSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(GraphProjectorModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.graphprojector");
  }
}
