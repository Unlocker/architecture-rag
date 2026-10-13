package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.identityresolution.FeatureKind;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
  void eventCounterIsRegisteredWithZeroBeforeFirstEvent() {
    for (String[] pair : new String[][] {{"eam", "PROJECTED"}, {"unknown", IngestionMetrics.STATUS_ERROR}}) {
      var c = registry.find(IngestionMetrics.EVENTS).tag("source", pair[0]).tag("status", pair[1]).counter();
      assertThat(c).isNotNull();
      assertThat(c.count()).isZero();
    }
  }

  @Test
  void eventCounterSeriesCountIsSourcesTimesStatuses() {
    assertThat(registry.find(IngestionMetrics.EVENTS).counters())
        .hasSize((SourceSystem.values().length + 1) * (ProcessingStatus.values().length + 1));
  }

  @Test
  void recordingDoesNotCreateDuplicateSeries() {
    int before = registry.find(IngestionMetrics.EVENTS).counters().size();
    metrics.recordEvent("eam", ProcessingStatus.PROJECTED);
    metrics.recordEvent("urn:corp:other-" + System.nanoTime(), null);

    assertThat(registry.find(IngestionMetrics.EVENTS).counters()).hasSize(before);
  }

  @Test
  void journalSourceUrnIsTaggedWithShortCode() {
    metrics.recordEvent("urn:corp:cmdb", ProcessingStatus.PROJECTED);

    assertThat(count("cmdb", "PROJECTED")).isEqualTo(1);
  }

  @Test
  void unknownSourceIsMappedToUnknownTag() {
    metrics.recordEvent("urn:corp:random-" + System.nanoTime(), ProcessingStatus.PROJECTED);

    assertThat(count("unknown", "PROJECTED")).isEqualTo(1);
  }

  @Test
  void nullStatusIsCountedAsError() {
    metrics.recordEvent("scm", null);

    assertThat(count("scm", IngestionMetrics.STATUS_ERROR)).isEqualTo(1);
  }

  @Test
  void unmappedSeriesExistsWithZeroBeforeFirstWarning() {
    var c = registry.find(IngestionMetrics.UNMAPPED_VALUES).tag("source", SourceSystem.EAM.code()).tag("property", "criticality").counter();

    assertThat(c).isNotNull();
    assertThat(c.count()).isZero();
  }

  @Test
  void unmappedCriticalityIncrementsCounter() {
    metrics.recordWarnings(SourceSystemCode.EAM, List.of("UNMAPPED_CRITICALITY"));
    metrics.recordWarnings(SourceSystemCode.EAM, List.of("UNMAPPED_CRITICALITY"));

    assertThat(registry.get(IngestionMetrics.UNMAPPED_VALUES).tag("source", SourceSystem.EAM.code())
        .tag("property", "criticality").counter().count()).isEqualTo(2);
  }

  @Test
  void candidatesOverflowSeriesExistsPerStrongFeatureWithZero() {
    for (String kind : List.of("hostname", "repository_url", "name")) {
      assertThat(registry.get(IngestionMetrics.CANDIDATES_OVERFLOW).tag("feature", kind).counter().count()).isZero();
    }
    assertThat(registry.find(IngestionMetrics.CANDIDATES_OVERFLOW).tag("feature", "owner").counter()).isNull();
  }

  @Test
  void candidatesOverflowIncrementsByFeatureKindOnly() {
    metrics.recordCandidatesOverflow(FeatureKind.HOSTNAME);
    metrics.recordCandidatesOverflow(FeatureKind.HOSTNAME);

    assertThat(registry.get(IngestionMetrics.CANDIDATES_OVERFLOW).tag("feature", "hostname").counter().count()).isEqualTo(2);
    assertThat(registry.get(IngestionMetrics.CANDIDATES_OVERFLOW).tag("feature", "name").counter().count()).isZero();
  }

  @Test
  void reconcileSkippedIsCountedBySourceAndReason() {
    metrics.recordReconcileSkipped(SourceSystemCode.EAM, "PARTIAL");

    assertThat(registry.get(IngestionMetrics.RECONCILE_SKIPPED).tag("source", "eam").tag("reason", "PARTIAL").counter().count())
        .isEqualTo(1);
  }

  @Test
  void otherWarningCodesAreNotCountedAndDoNotCreateSeries() {
    int before = registry.find(IngestionMetrics.UNMAPPED_VALUES).counters().size();

    metrics.recordWarnings(SourceSystemCode.EAM, List.of("DEPENDS_ON_NOT_SUPPORTED", "DEPLOYMENT_SERVICE_UNRESOLVED"));
    metrics.recordWarnings(SourceSystemCode.EAM, List.of("UNMAPPED_CRITICALITY"));

    assertThat(registry.find(IngestionMetrics.UNMAPPED_VALUES).counters()).hasSize(before);
    assertThat(registry.find(IngestionMetrics.UNMAPPED_VALUES).counters().stream().mapToDouble(c -> c.count()).sum()).isEqualTo(1);
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
