package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyncRunCountsTest {

  @Test
  void appliedCountsProjectedDuplicateAndOldVersionFailedCountsQuarantined() {
    var counts =
        SyncRunCounts.of(
            9,
            Map.of(
                ProcessingStatus.PROJECTED, 4L,
                ProcessingStatus.DUPLICATE, 2L,
                ProcessingStatus.IGNORED_OLD_VERSION, 1L,
                ProcessingStatus.QUARANTINED, 3L,
                ProcessingStatus.RETRYING, 5L,
                ProcessingStatus.RECEIVED, 6L));

    assertThat(counts).isEqualTo(new SyncRunCounts(9, 7, 3));
  }

  @Test
  void runWithoutEventsHasZeroCounters() {
    assertThat(SyncRunCounts.of(0, Map.of())).isEqualTo(new SyncRunCounts(0, 0, 0));
  }
}
