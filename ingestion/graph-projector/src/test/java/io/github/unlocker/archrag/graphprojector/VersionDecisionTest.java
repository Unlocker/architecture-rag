package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.eventschemas.SourceVersion;
import org.junit.jupiter.api.Test;

class VersionDecisionTest {

  private static SourceVersion v(String value) {
    return new SourceVersion(value);
  }

  @Test
  void unknownRecordIsApplied() {
    assertThat(VersionDecision.forUpsert(v("1"), null)).isEqualTo(VersionDecision.APPLY);
    assertThat(VersionDecision.forTombstone(v("1"), null, false)).isEqualTo(VersionDecision.APPLY);
  }

  @Test
  void versionsAreComparedAsNumbersNotStrings() {
    assertThat(VersionDecision.forUpsert(v("99"), v("184"))).isEqualTo(VersionDecision.OLD);
    assertThat(VersionDecision.forUpsert(v("184"), v("99"))).isEqualTo(VersionDecision.APPLY);
  }

  @Test
  void equalVersionIsSameNotOld() {
    assertThat(VersionDecision.forUpsert(v("7"), v("7"))).isEqualTo(VersionDecision.SAME);
  }

  @Test
  void tombstoneWithEqualVersionAppliesOnlyToActiveRecord() {
    assertThat(VersionDecision.forTombstone(v("7"), v("7"), true)).isEqualTo(VersionDecision.APPLY);
    assertThat(VersionDecision.forTombstone(v("7"), v("7"), false)).isEqualTo(VersionDecision.SAME);
    assertThat(VersionDecision.forTombstone(v("6"), v("7"), true)).isEqualTo(VersionDecision.OLD);
  }
}
