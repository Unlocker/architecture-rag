package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncRunCountsTest {

  @Test
  void appliedCountsProjectedDuplicateAndOldVersionFailedCountsQuarantinedAndRetrying() {
    var counts =
        SyncRunCounts.of(
            Map.of(
                ProcessingStatus.PROJECTED, 4L,
                ProcessingStatus.DUPLICATE, 2L,
                ProcessingStatus.IGNORED_OLD_VERSION, 1L,
                ProcessingStatus.QUARANTINED, 3L,
                ProcessingStatus.RETRYING, 5L,
                ProcessingStatus.RECEIVED, 6L));

    assertThat(counts).isEqualTo(new SyncRunCounts(21, 7, 8));
  }

  @Test
  void runWithoutEventsHasZeroCounters() {
    assertThat(SyncRunCounts.of(Map.of())).isEqualTo(new SyncRunCounts(0, 0, 0));
  }
}
