package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Метрики ingestion для дашборда PoC: счётчик обработанных событий и gauges по журналу PostgreSQL.
 *
 * <p>Теги ограничены перечислениями: {@code source} (из {@link SourceSystem}, у gauges) и {@code status}
 * ({@link ProcessingStatus} или {@code ERROR}); идентификаторы объектов и тексты ошибок в теги не попадают.
 * Gauges ({@code archrag.ingestion.sync.lag}, {@code archrag.ingestion.dlq.open}) пересчитываются
 * {@link #refresh()} по расписанию, а не при каждом сборе; источник без строк получает {@code 0}.
 */
public class IngestionMetrics {

  /** Значение тега {@code status}, когда обработка события бросила неожиданное исключение. */
  public static final String STATUS_ERROR = "ERROR";

  static final String EVENTS = "archrag.ingestion.events";
  static final String SYNC_LAG = "archrag.ingestion.sync.lag";
  static final String DLQ_OPEN = "archrag.ingestion.dlq.open";

  private static final String SOURCE_PREFIX = "urn:corp:";

  private static final Logger LOG = LoggerFactory.getLogger(IngestionMetrics.class);

  /** Нетерминальные статусы: событие ещё ждёт обработки (включая маркеры snapshot-complete). */
  private static final Set<ProcessingStatus> WAITING = Set.of(ProcessingStatus.RECEIVED, ProcessingStatus.VALIDATED,
      ProcessingStatus.NORMALIZED, ProcessingStatus.RESOLVED, ProcessingStatus.RETRYING);

  // Литералы статусов подставляются из enum, а не из ввода.
  private static final String LAG_SQL = "SELECT source, EXTRACT(EPOCH FROM (now() - min(received_at))) AS lag "
      + "FROM inbox_event WHERE status IN ("
      + WAITING.stream().map(s -> "'" + s.name() + "'").sorted().collect(Collectors.joining(", "))
      + ") GROUP BY source";
  private static final String DLQ_SQL =
      "SELECT source, count(*) AS open FROM dlq_entry WHERE replayed_at IS NULL GROUP BY source";

  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;
  private final MultiGauge lag;
  private final MultiGauge dlqOpen;

  public IngestionMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
    this.registry = registry;
    this.jdbc = jdbc;
    this.lag = MultiGauge.builder(SYNC_LAG).baseUnit("seconds")
        .description("Age of the oldest waiting inbox event per source").register(registry);
    this.dlqOpen = MultiGauge.builder(DLQ_OPEN)
        .description("Open (not replayed) DLQ entries per source").register(registry);
    register(lag, zeroes());
    register(dlqOpen, zeroes());
  }

  /**
   * Считает событие с итоговым статусом; {@code source} — значение из журнала ({@code urn:corp:eam}).
   * {@code null} (неожиданное исключение) учитывается как {@code ERROR}.
   */
  public void recordEvent(String source, ProcessingStatus status) {
    registry.counter(EVENTS, "source", sourceTag(source), "status", status == null ? STATUS_ERROR : status.name()).increment();
  }

  /**
   * Пересчитывает gauges двумя агрегирующими запросами. При ошибке БД прежние значения остаются, в лог идёт только
   * класс исключения.
   */
  @Scheduled(fixedDelayString = "${archrag.metrics.refresh-interval:15s}")
  public void refresh() {
    try {
      Map<String, Double> lags = zeroes();
      jdbc.query(LAG_SQL, rs -> {
        lags.put(sourceTag(rs.getString("source")), Math.max(0d, rs.getDouble("lag")));
      });
      Map<String, Double> open = zeroes();
      jdbc.query(DLQ_SQL, rs -> {
        open.put(sourceTag(rs.getString("source")), (double) rs.getLong("open"));
      });
      register(lag, lags);
      register(dlqOpen, open);
    } catch (DataAccessException e) {
      LOG.warn("ingestion metrics refresh failed: cause={}", e.getClass().getName());
    }
  }

  /** {@code urn:corp:eam} (значение {@code source} в журнале) → {@code eam}: тег совпадает с {@link SourceSystem#code()}. */
  static String sourceTag(String source) {
    return source.startsWith(SOURCE_PREFIX) ? source.substring(SOURCE_PREFIX.length()) : source;
  }

  private static Map<String, Double> zeroes() {
    Map<String, Double> m = new HashMap<>();
    Arrays.stream(SourceSystem.values()).forEach(s -> m.put(s.code(), 0d));
    return m;
  }

  private static void register(MultiGauge gauge, Map<String, Double> values) {
    List<MultiGauge.Row<Number>> rows = new ArrayList<>();
    values.forEach((source, v) -> rows.add(MultiGauge.Row.of(Tags.of("source", source), v)));
    gauge.register(rows, true);
  }
}
