package io.github.unlocker.archrag.eventschemas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceVersionTest {

  @Test
  void comparesNumericallyNotLexically() {
    assertThat(new SourceVersion("99").isOlderThan(new SourceVersion("184"))).isTrue();
    assertThat(new SourceVersion("184").isOlderThan(new SourceVersion("99"))).isFalse();
    assertThat(new SourceVersion("184").isOlderThan(new SourceVersion("184"))).isFalse();
  }

  @Test
  void rejectsNonNumeric() {
    assertThatThrownBy(() -> new SourceVersion("v2")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceVersion(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
