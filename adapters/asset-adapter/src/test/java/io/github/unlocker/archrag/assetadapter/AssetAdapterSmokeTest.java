package io.github.unlocker.archrag.assetadapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssetAdapterSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(AssetAdapterModule.class.getPackageName()).isEqualTo("io.github.unlocker.archrag.assetadapter");
  }
}
