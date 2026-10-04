package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CrosswalkTest {

  private static final SourceKey EAM = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", "PAY");
  private static final SourceKey SCM = new SourceKey(SourceSystemCode.SCM, "CATALOG_SYSTEM", "42");

  @Test
  void keepsBothKeys() {
    var cw = new Crosswalk(EAM, SCM, "admin", "same system", Instant.parse("2026-10-04T00:00:00Z"));

    assertThat(cw.left()).isEqualTo(EAM);
    assertThat(cw.right()).isEqualTo(SCM);
  }

  @Test
  void rejectsLinkToItself() {
    assertThatThrownBy(() -> new Crosswalk(EAM, EAM, "admin", "same system", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankApprover() {
    assertThatThrownBy(() -> new Crosswalk(EAM, SCM, " ", "r", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankReason() {
    assertThatThrownBy(() -> new Crosswalk(EAM, SCM, "admin", " ", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
