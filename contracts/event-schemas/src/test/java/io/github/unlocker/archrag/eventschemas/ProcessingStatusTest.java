package io.github.unlocker.archrag.eventschemas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ProcessingStatusTest {

  @Test
  void happyPathMovesForward() {
    assertThat(ProcessingStatus.RECEIVED.canTransitionTo(ProcessingStatus.VALIDATED)).isTrue();
    assertThat(ProcessingStatus.VALIDATED.canTransitionTo(ProcessingStatus.NORMALIZED)).isTrue();
    assertThat(ProcessingStatus.NORMALIZED.canTransitionTo(ProcessingStatus.RESOLVED)).isTrue();
    assertThat(ProcessingStatus.RESOLVED.canTransitionTo(ProcessingStatus.PROJECTED)).isTrue();
    assertThat(ProcessingStatus.PROJECTED.canTransitionTo(ProcessingStatus.SUPERSEDED)).isTrue();
  }

  @Test
  void projectedCannotRollBack() {
    assertThat(ProcessingStatus.PROJECTED.canTransitionTo(ProcessingStatus.RETRYING)).isFalse();
    assertThat(ProcessingStatus.PROJECTED.canTransitionTo(ProcessingStatus.RECEIVED)).isFalse();
  }

  @Test
  void terminalStatesOnlyStayPut() {
    for (var terminal : EnumSet.of(ProcessingStatus.SUPERSEDED, ProcessingStatus.DUPLICATE,
        ProcessingStatus.IGNORED_OLD_VERSION)) {
      for (var next : ProcessingStatus.values()) {
        assertThat(terminal.canTransitionTo(next)).as(terminal + " -> " + next).isEqualTo(terminal == next);
      }
    }
  }

  @Test
  void quarantineExitsOnlyThroughRetrying() {
    for (var next : ProcessingStatus.values()) {
      boolean allowed = next == ProcessingStatus.QUARANTINED || next == ProcessingStatus.RETRYING;
      assertThat(ProcessingStatus.QUARANTINED.canTransitionTo(next)).as("QUARANTINED -> " + next).isEqualTo(allowed);
    }
  }

  @Test
  void failuresAreReachableFromActiveStages() {
    assertThat(ProcessingStatus.RESOLVED.canTransitionTo(ProcessingStatus.QUARANTINED)).isTrue();
    assertThat(ProcessingStatus.RETRYING.canTransitionTo(ProcessingStatus.PROJECTED)).isTrue();
  }
}
