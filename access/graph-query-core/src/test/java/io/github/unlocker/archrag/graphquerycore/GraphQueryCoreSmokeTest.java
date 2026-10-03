package io.github.unlocker.archrag.graphquerycore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GraphQueryCoreSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(GraphQueryCoreModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.graphquerycore");
  }
}
