package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphProjectorSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(GraphProjectorModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.graphprojector");
  }
}
