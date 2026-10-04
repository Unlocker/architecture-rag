package io.github.unlocker.archrag.mcpserver.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolCallAuditorTest {

  private SimpleMeterRegistry meters;
  private ToolCallAuditor auditor;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    var observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    auditor = new ToolCallAuditor(observations, meters);
  }

  @Test
  void completedCallRecordsTimerWithToolAndDecisionOnly() {
    var observation = auditor.start("ping");
    auditor.completed(
        observation,
        "ping",
        Map.of("secretArg", "top-secret-value"),
        ToolCallContext.open(),
        Duration.ofMillis(5),
        ToolDecision.ALLOWED,
        null);
    ToolCallContext.close();

    var timer = meters.get(ToolCallAuditor.OBSERVATION).timer();
    assertThat(timer.getId().getTag("tool")).isEqualTo("ping");
    assertThat(timer.getId().getTag("decision")).isEqualTo("ALLOWED");
    assertThat(timer.getId().getTags()).extracting(t -> t.getValue()).doesNotContain("top-secret-value");
    assertThat(timer.count()).isEqualTo(1);
  }

  @Test
  void rowsSummaryCountsRowsPerTool() {
    var context = ToolCallContext.open();
    ToolCallContext.record(
        new io.github.unlocker.archrag.graphquerycore.QueryResult(
            "t1", java.util.List.of(Map.of("a", 1), Map.of("a", 2)), false, 2, Duration.ZERO));
    ToolCallContext.close();
    auditor.completed(
        auditor.start("q"), "q", Map.of(), context, Duration.ZERO, ToolDecision.ALLOWED, null);

    var summary = meters.get(ToolCallAuditor.ROWS_SUMMARY).tag("tool", "q").summary();
    assertThat(summary.count()).isEqualTo(1);
    assertThat(summary.totalAmount()).isEqualTo(2.0);
  }

  @Test
  void deniedUnknownToolUsesFixedTagNotUserInput() {
    auditor.denied("attacker-chosen-name", ToolDecision.DENIED_UNKNOWN_TOOL);
    auditor.denied("ping", ToolDecision.DENIED_SCOPE);

    var tools =
        meters.getMeters().stream()
            .map(Meter::getId)
            .filter(id -> id.getName().equals(ToolCallAuditor.OBSERVATION))
            .map(id -> id.getTag("tool") + "/" + id.getTag("decision"))
            .toList();
    assertThat(tools).contains("unknown/DENIED_UNKNOWN_TOOL", "ping/DENIED_SCOPE");
    assertThat(tools).noneMatch(t -> t.contains("attacker"));
  }

  @Test
  void sanitizeReplacesControlCharsAndTruncates() {
    assertThat(ToolCallAuditor.sanitize("a\nb\rc")).isEqualTo("a?b?c");
    assertThat(ToolCallAuditor.sanitize("x".repeat(200))).hasSize(65);
    assertThat(ToolCallAuditor.sanitize(null)).isNull();
  }

  @Test
  void auditFailureDoesNotPropagate() {
    var observation = auditor.start("ping");
    // null-контекст вызывает NPE внутри completed; он должен быть проглочен с предупреждением.
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                auditor.completed(
                    observation, "ping", Map.of(), null, Duration.ZERO, ToolDecision.ALLOWED, null))
        .doesNotThrowAnyException();
  }
}
