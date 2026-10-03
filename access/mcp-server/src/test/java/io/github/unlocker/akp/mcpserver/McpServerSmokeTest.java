package io.github.unlocker.akp.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpServerSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(McpServerModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.mcpserver");
  }
}
