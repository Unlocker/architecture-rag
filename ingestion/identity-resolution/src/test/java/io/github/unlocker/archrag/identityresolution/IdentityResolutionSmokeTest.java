package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityResolutionSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(IdentityResolutionModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.identityresolution");
  }
}
