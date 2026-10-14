package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.graphprojector.SyncRunCounts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Фоновая чистка: зависшие прогоны {@code SyncRun}, retention прогонов и retention {@code admin_audit}.
 * Живёт в ingestion-service, потому что у консоли есть только {@code SELECT} и доступ на чтение к графу.
 *
 * <p>Инварианты: {@code RUNNING} не удаляется; удаление прогонов идёт небольшими пачками, чтобы не держать
 * большую транзакцию (рёбра {@code PROCESSED} уходят вместе с узлом); ошибка одного шага не мешает остальным,
 * в лог идёт только класс исключения.
 */
public class HousekeepingJob {

  /** Прогонов в одной транзакции удаления: у каждого тысячи рёбер {@code PROCESSED}. */
  static final int DELETE_BATCH = 10;

  private static final Logger LOG = LoggerFactory.getLogger(HousekeepingJob.class);

  private static final String STALE_RUNS =
      "MATCH (s:SyncRun) WHERE s.status = 'RUNNING' AND s.startedAt < $cutoff "
          + "RETURN s.runId AS runId, s.adapter AS adapter";

  // Условие RUNNING в записи: поздний маркер уже мог закрыть прогон, его итог таймаут не перезаписывает.
  private static final String FAIL_STALE =
      "MATCH (s:SyncRun {runId: $runId}) WHERE s.status = 'RUNNING' "
          + "SET s.status = 'FAILED', s.endedAt = $now, s.fetched = $fetched, s.applied = $applied, s.failed = $failed "
          + "RETURN count(s) AS n";

  // Терминальные прогоны адаптера, упорядоченные от новых к старым; пропускаем keep последних.
  private static final String DELETE_OLD =
      "MATCH (s:SyncRun) WHERE s.status IN ['SUCCEEDED', 'FAILED'] "
          + "WITH s.adapter AS adapter, s ORDER BY coalesce(s.endedAt, s.startedAt) DESC "
          + "WITH adapter, collect(s) AS runs "
          + "UNWIND runs[$keep..] AS old "
          + "WITH old LIMIT $batch "
          + "DETACH DELETE old "
          + "RETURN count(*) AS n";

  /** Префикс id маркера {@code snapshot-complete}: маркер в счётчики не входит. */
  private static final String MARKER_PREFIX = "snapshot-complete:";

  private final Driver driver;
  private final EventJournal journal;
  private final JdbcTemplate jdbc;
  private final SyncRunProperties props;
  private final AdminProperties admin;
  private final Clock clock;

  public HousekeepingJob(
      Driver driver, EventJournal journal, JdbcTemplate jdbc, SyncRunProperties props, AdminProperties admin,
      Clock clock) {
    this.driver = driver;
    this.journal = journal;
    this.jdbc = jdbc;
    this.props = props;
    this.admin = admin;
    this.clock = clock;
  }

  /** Один проход чистки: граф, затем журнал аудита. */
  @Scheduled(fixedDelayString = "${archrag.sync-run.housekeeping-interval:1h}")
  public void run() {
    try {
      failStaleRuns();
      deleteOldRuns();
    } catch (RuntimeException e) {
      LOG.warn("sync run housekeeping failed: cause={}", e.getClass().getName());
    }
    try {
      cleanAudit();
    } catch (DataAccessException e) {
      LOG.warn("admin audit housekeeping failed: cause={}", e.getClass().getName());
    }
  }

  /**
   * Переводит {@code RUNNING}-прогоны старше {@code stale-after} в {@code FAILED} со счётчиками из журнала
   * ({@code fetched} — все события прогона); возвращает число переведённых.
   */
  long failStaleRuns() {
    Instant now = clock.instant();
    OffsetDateTime cutoff = now.minus(props.staleAfter()).atOffset(ZoneOffset.UTC);
    List<Map<String, Object>> stale;
    try (Session session = driver.session()) {
      stale =
          session.executeRead(
              tx ->
                  tx.run(STALE_RUNS, Map.of("cutoff", cutoff)).list(r -> Map.<String, Object>of(
                      "runId", r.get("runId").asString(), "adapter", r.get("adapter").asString("")))); 
    }
    long failed = 0;
    for (Map<String, Object> run : stale) {
      String runId = (String) run.get("runId");
      // Журнал читается вне транзакции Neo4j: в повторяемой функции драйвера побочных эффектов быть не должно.
      String source = sourceUrn((String) run.get("adapter"));
      Map<ProcessingStatus, Long> byStatus = journal.statusCounts(source, runId, MARKER_PREFIX + runId);
      SyncRunCounts counts = SyncRunCounts.of(byStatus);
      Map<String, Object> params =
          Map.of("runId", runId, "now", now.atOffset(ZoneOffset.UTC), "fetched", counts.fetched(),
              "applied", counts.applied(), "failed", counts.failed());
      try (Session session = driver.session()) {
        failed += session.executeWrite(tx -> tx.run(FAIL_STALE, params).single().get("n").asLong());
      }
    }
    return failed;
  }

  /** Удаляет терминальные прогоны сверх {@code keep-per-source} на адаптер; возвращает число удалённых. */
  long deleteOldRuns() {
    long total = 0;
    long deleted;
    do {
      Map<String, Object> params = Map.of("keep", props.keepPerSource(), "batch", DELETE_BATCH);
      try (Session session = driver.session()) {
        deleted = session.executeWrite(tx -> tx.run(DELETE_OLD, params).single().get("n").asLong());
      }
      total += deleted;
    } while (deleted == DELETE_BATCH);
    return total;
  }

  /** Переводит брошенные {@code STARTED} в {@code FAILED} и удаляет записи старше retention. */
  void cleanAudit() {
    Instant now = clock.instant();
    Timestamp stale = Timestamp.from(now.minus(admin.auditStaleAfter()));
    jdbc.update(
        "UPDATE admin_audit SET status = 'FAILED', error = 'abandoned', finished_at = ?"
            + " WHERE status = 'STARTED' AND started_at < ?",
        Timestamp.from(now), stale);
    jdbc.update(
        "DELETE FROM admin_audit WHERE started_at < ?",
        Timestamp.from(now.minus(admin.auditRetention())));
  }

  /** {@code SyncRun.adapter} ({@code EAM}) → {@code urn:corp:eam}; неизвестное значение отдаёт пустой журнал. */
  private static String sourceUrn(String adapter) {
    try {
      return SourceSystemCode.valueOf(adapter.toUpperCase(Locale.ROOT)).urn();
    } catch (IllegalArgumentException e) {
      return SourceSystemCode.URN_PREFIX + adapter.toLowerCase(Locale.ROOT);
    }
  }
}
