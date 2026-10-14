package io.github.unlocker.archrag.adminconsole;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adminconsole.pg.SyncSources;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SyncSourcesTest {

  @Test
  void codesMatchSourceSystemCodeInOrder() {
    assertThat(SyncSources.CODES)
        .containsExactlyElementsOf(
            Arrays.stream(SourceSystemCode.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList());
  }
}
