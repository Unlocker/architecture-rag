package io.github.unlocker.akp.deploymapadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeploymapAdapterSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(DeploymapAdapterModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.deploymapadapter");
  }
}
