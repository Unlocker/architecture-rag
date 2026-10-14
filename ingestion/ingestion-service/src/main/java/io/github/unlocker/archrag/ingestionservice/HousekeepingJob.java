package io.github.unlocker.archrag.ingestionservice;

import java.time.Clock;
import java.time.Instant;
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

  private static final String FAIL_STALE =
      "MATCH (s:SyncRun) WHERE s.status = 'RUNNING' AND s.startedAt < $cutoff "
          + "SET s.status = 'FAILED', s.endedAt = $now "
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

  private final Driver driver;
  private final JdbcTemplate jdbc;
  private final SyncRunProperties props;
  private final AdminProperties admin;
  private final Clock clock;

  public HousekeepingJob(Driver driver, JdbcTemplate jdbc, SyncRunProperties props, AdminProperties admin, Clock clock) {
    this.driver = driver;
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

  /** Переводит {@code RUNNING}-прогоны старше {@code stale-after} в {@code FAILED}; возвращает их число. */
  long failStaleRuns() {
    Instant now = clock.instant();
    Map<String, Object> params =
        Map.of("cutoff", now.minus(props.staleAfter()).atOffset(java.time.ZoneOffset.UTC), "now",
            now.atOffset(java.time.ZoneOffset.UTC));
    try (Session session = driver.session()) {
      return session.executeWrite(tx -> tx.run(FAIL_STALE, params).single().get("n").asLong());
    }
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
    java.sql.Timestamp stale = java.sql.Timestamp.from(now.minus(props.staleAfter()));
    jdbc.update(
        "UPDATE admin_audit SET status = 'FAILED', error = 'abandoned', finished_at = ?"
            + " WHERE status = 'STARTED' AND started_at < ?",
        java.sql.Timestamp.from(now), stale);
    jdbc.update(
        "DELETE FROM admin_audit WHERE started_at < ?",
        java.sql.Timestamp.from(now.minus(admin.auditRetention())));
  }
}
