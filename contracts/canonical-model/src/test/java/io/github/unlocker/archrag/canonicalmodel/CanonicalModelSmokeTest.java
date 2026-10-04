package io.github.unlocker.archrag.canonicalmodel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalModelSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(CanonicalModelModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.canonicalmodel");
  }
}
