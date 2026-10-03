package io.github.unlocker.akp.sourcespi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceSpiSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(SourceSpiModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.sourcespi");
  }
}
