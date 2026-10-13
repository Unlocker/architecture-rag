package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.identityresolution.FeatureKind;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Счётчик событий, как и gauges, существует с нулём до первого события: все пары {@code source} × {@code status}
 * регистрируются в конструкторе, иначе перезапущенный процесс без событий не отдаёт ряд в OTLP.
 *
 * <p>Счётчик {@code archrag.normalizer.unmapped.values} ({@code source}, {@code property}) растёт на каждую
 * нормализацию, давшую код предупреждения о значении вне словаря, включая повтор, replay и rebuild: это метрика
 * темпа (в Prometheus — через {@code increase()}). Коды вне {@link #UNMAPPED_PROPERTIES} не считаются; в теги и логи
 * попадают только коды, без значений источника.
 *
 * <p>Счётчик {@code archrag.identity.candidates.overflow} ({@code feature}) растёт, когда у сильного признака
 * совпадений больше предела и кандидаты по нему не создаются; {@code archrag.ingestion.reconcile.skipped}
 * ({@code source}, {@code reason}) — когда reconcile не выполнен из-за {@code PARTIAL} или превышения порога
 * массового закрытия.
 */
public class IngestionMetrics {

  /** Значение тега {@code status}, когда обработка события бросила неожиданное исключение. */
  public static final String STATUS_ERROR = "ERROR";

  static final String EVENTS = "archrag.ingestion.events";
  static final String SYNC_LAG = "archrag.ingestion.sync.lag";
  static final String DLQ_OPEN = "archrag.ingestion.dlq.open";
  static final String UNMAPPED_VALUES = "archrag.normalizer.unmapped.values";
  static final String CANDIDATES_OVERFLOW = "archrag.identity.candidates.overflow";
  static final String RECONCILE_SKIPPED = "archrag.ingestion.reconcile.skipped";

  /** Код предупреждения нормализатора → свойство, чьё значение вышло за словарь. Фиксированная таблица ограничивает кардинальность. */
  static final Map<String, String> UNMAPPED_PROPERTIES = Map.of("UNMAPPED_CRITICALITY", "criticality");
  private static final Map<SourceSystemCode, List<String>> UNMAPPED_SOURCES =
      Map.of(SourceSystemCode.EAM, List.copyOf(UNMAPPED_PROPERTIES.values()));

  private static final String SOURCE_PREFIX = "urn:corp:";
  static final String UNKNOWN_SOURCE = "unknown";
  private static final Set<String> KNOWN_SOURCES =
      Arrays.stream(SourceSystem.values()).map(SourceSystem::code).collect(Collectors.toUnmodifiableSet());

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
    for (String source : zeroes().keySet()) {
      for (ProcessingStatus status : ProcessingStatus.values()) {
        eventCounter(source, status.name());
      }
      eventCounter(source, STATUS_ERROR);
    }
    UNMAPPED_SOURCES.forEach((source, properties) -> properties.forEach(p -> unmappedCounter(source, p)));
    for (FeatureKind kind : FeatureKind.values()) {
      if (kind.strong()) {
        overflowCounter(kind);
      }
    }
  }

  /**
   * Считает признак, у которого совпадений больше предела {@code PostgresIdentityCandidates.MAX_MATCHES}: кандидаты
   * по нему не созданы. Тег {@code feature} — вид признака из {@link FeatureKind}, значения признака в метрике нет.
   */
  public void recordCandidatesOverflow(FeatureKind kind) {
    overflowCounter(kind).increment();
  }

  private Counter overflowCounter(FeatureKind kind) {
    return registry.counter(CANDIDATES_OVERFLOW, "feature", kind.name().toLowerCase(Locale.ROOT));
  }

  /**
   * Считает reconcile, не выполненный по защите от неполного snapshot: {@code reason} — {@code PARTIAL} или код
   * {@code SnapshotRejectedException}; значения теги берут только из этих констант.
   */
  public void recordReconcileSkipped(SourceSystemCode source, String reason) {
    registry.counter(RECONCILE_SKIPPED, "source", source.name().toLowerCase(Locale.ROOT), "reason", reason).increment();
  }

  /**
   * Считает коды предупреждений нормализации события источника {@code source}; коды вне таблицы
   * {@link #UNMAPPED_PROPERTIES} (например {@code DEPENDS_ON_NOT_SUPPORTED}) игнорируются.
   */
  public void recordWarnings(SourceSystemCode source, List<String> warnings) {
    for (String code : warnings) {
      String property = UNMAPPED_PROPERTIES.get(code);
      if (property != null) {
        unmappedCounter(source, property).increment();
      }
    }
  }

  private Counter unmappedCounter(SourceSystemCode source, String property) {
    // Тег source совпадает с SourceSystem.code(), как у счётчика событий.
    return registry.counter(UNMAPPED_VALUES, "source", source.name().toLowerCase(Locale.ROOT), "property", property);
  }

  /**
   * Считает событие с итоговым статусом; {@code source} — значение из журнала ({@code urn:corp:eam}).
   * {@code null} (неожиданное исключение) учитывается как {@code ERROR}.
   */
  public void recordEvent(String source, ProcessingStatus status) {
    eventCounter(sourceTag(source), status == null ? STATUS_ERROR : status.name()).increment();
  }

  /** Единая точка формирования тегов счётчика: имена и порядок совпадают у предрегистрации и у {@link #recordEvent}. */
  private Counter eventCounter(String source, String status) {
    return registry.counter(EVENTS, "source", source, "status", status);
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
        lags.merge(sourceTag(rs.getString("source")), Math.max(0d, rs.getDouble("lag")), Math::max);
      });
      Map<String, Double> open = zeroes();
      jdbc.query(DLQ_SQL, rs -> {
        open.merge(sourceTag(rs.getString("source")), (double) rs.getLong("open"), Double::sum);
      });
      register(lag, lags);
      register(dlqOpen, open);
    } catch (DataAccessException e) {
      LOG.warn("ingestion metrics refresh failed: cause={}", e.getClass().getName());
    }
  }

  /** {@code urn:corp:eam} (значение {@code source} в журнале) → {@code eam}: тег совпадает с {@link SourceSystem#code()}. */
  static String sourceTag(String source) {
    String code = source.startsWith(SOURCE_PREFIX) ? source.substring(SOURCE_PREFIX.length()) : source;
    // Значения вне SourceSystem не плодят серии: кардинальность ограничена перечислением.
    return KNOWN_SOURCES.contains(code) ? code : UNKNOWN_SOURCE;
  }

  private static Map<String, Double> zeroes() {
    Map<String, Double> m = new HashMap<>();
    KNOWN_SOURCES.forEach(code -> m.put(code, 0d));
    m.put(UNKNOWN_SOURCE, 0d);
    return m;
  }

  private static void register(MultiGauge gauge, Map<String, Double> values) {
    List<MultiGauge.Row<Number>> rows = new ArrayList<>();
    values.forEach((source, v) -> rows.add(MultiGauge.Row.of(Tags.of("source", source), v)));
    gauge.register(rows, true);
  }
}
