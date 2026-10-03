package io.github.unlocker.akp.eventschemas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventSchemasSmokeTest {

  @Test
  void moduleMarkerIsOnClasspath() {
    assertThat(EventSchemasModule.class.getPackageName()).isEqualTo("io.github.unlocker.akp.eventschemas");
  }
}
