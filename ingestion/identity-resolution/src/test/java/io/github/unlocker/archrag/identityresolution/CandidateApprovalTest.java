package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.identityresolution.CandidateApproval.Survivor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateApprovalTest {

  private static final Instant T = Instant.parse("2026-10-14T09:00:00Z");
  private static final SourceKey A = new SourceKey(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", "a");
  private static final SourceKey B = new SourceKey(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", "b");

  @Test
  void survivorSelectsTheKeyAsGiven() {
    var left = new CandidateApproval(A, B, Survivor.LEFT, "op", "same host", T);
    var right = new CandidateApproval(A, B, Survivor.RIGHT, "op", "same host", T);

    assertThat(left.survivorKey()).isEqualTo(A);
    assertThat(left.movedKey()).isEqualTo(B);
    assertThat(right.survivorKey()).isEqualTo(B);
    assertThat(right.movedKey()).isEqualTo(A);
  }

  @Test
  void rejectsSameKeyBlankAuditFieldsAndMissingSurvivor() {
    assertThatThrownBy(() -> new CandidateApproval(A, A, Survivor.LEFT, "op", "r", T)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CandidateApproval(A, B, Survivor.LEFT, " ", "r", T)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CandidateApproval(A, B, Survivor.LEFT, "op", "", T)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CandidateApproval(A, B, null, "op", "r", T)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void relocationChangesGidOnlyWhenGidsDiffer() {
    UUID gid = UUID.randomUUID();

    assertThat(new CandidateRelocation(B, A, UUID.randomUUID(), gid, true).changesGid()).isTrue();
    assertThat(new CandidateRelocation(B, A, gid, gid, false).changesGid()).isFalse();
  }
}
