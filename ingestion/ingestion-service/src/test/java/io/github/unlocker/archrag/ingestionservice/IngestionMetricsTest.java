package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Счётчик по статусу и нулевые gauges на пустой очереди; SQL-часть проверяет {@code IngestionMetricsIT}. */
class IngestionMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  // query(...) на моке ничего не возвращает: журнал пуст.
  private final IngestionMetrics metrics = new IngestionMetrics(registry, mock(JdbcTemplate.class));

  @Test
  void countsEventsBySourceAndStatus() {
    metrics.recordEvent("eam", ProcessingStatus.PROJECTED);
    metrics.recordEvent("eam", ProcessingStatus.PROJECTED);
    metrics.recordEvent("eam", ProcessingStatus.QUARANTINED);

    assertThat(count("eam", "PROJECTED")).isEqualTo(2);
    assertThat(count("eam", "QUARANTINED")).isEqualTo(1);
    assertThat(count("scm", "PROJECTED")).isZero();
  }

  @Test
  void journalSourceUrnIsTaggedWithShortCode() {
    metrics.recordEvent("urn:corp:cmdb", ProcessingStatus.PROJECTED);

    assertThat(count("cmdb", "PROJECTED")).isEqualTo(1);
  }

  @Test
  void nullStatusIsCountedAsError() {
    metrics.recordEvent("scm", null);

    assertThat(count("scm", IngestionMetrics.STATUS_ERROR)).isEqualTo(1);
  }

  @Test
  void everySourceHasLagAndDlqGaugeEqualToZeroOnEmptyJournal() {
    metrics.refresh();

    for (SourceSystem s : SourceSystem.values()) {
      assertThat(registry.get(IngestionMetrics.SYNC_LAG).tag("source", s.code()).gauge().value()).isZero();
      assertThat(registry.get(IngestionMetrics.DLQ_OPEN).tag("source", s.code()).gauge().value()).isZero();
    }
  }

  private double count(String source, String status) {
    var c = registry.find(IngestionMetrics.EVENTS).tag("source", source).tag("status", status).counter();
    return c == null ? 0 : c.count();
  }
}
