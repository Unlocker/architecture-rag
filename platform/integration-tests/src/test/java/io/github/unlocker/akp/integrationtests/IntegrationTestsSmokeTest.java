package io.github.unlocker.akp.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationTestsSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    org.junit.jupiter.api.Assertions.fail("temporary: proves CI goes red");
    assertThat(IntegrationTestsModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.integrationtests");
  }
}
