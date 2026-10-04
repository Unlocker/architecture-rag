package io.github.unlocker.archrag.scmadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScmAdapterSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(ScmAdapterModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.scmadapter");
  }
}
