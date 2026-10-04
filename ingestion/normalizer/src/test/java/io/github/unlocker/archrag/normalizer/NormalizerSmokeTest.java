package io.github.unlocker.archrag.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizerSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(NormalizerModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.normalizer");
  }
}
