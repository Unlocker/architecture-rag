package io.github.unlocker.archrag.eamadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EamAdapterSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(EamAdapterModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.eamadapter");
  }
}
