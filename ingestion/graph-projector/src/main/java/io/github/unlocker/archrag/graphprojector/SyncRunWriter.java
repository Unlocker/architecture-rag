package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.provenance.SyncRunStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Запись итога прогона в узел {@code :SyncRun}. Отдельная write-транзакция, не связанная с проекцией событий.
 *
 * <p>Инварианты: узел ищется по {@code runId} (уникален); {@code SUCCEEDED} финален и не перезаписывается;
 * {@code FAILED} можно перезаписать в {@code SUCCEEDED} (повтор маркера); счётчики присваиваются, повтор идемпотентен;
 * если узла нет (прогон без событий), он создаётся.
 */
public final class SyncRunWriter {

  private static final String CLOSE =
      "MERGE (s:SyncRun {runId: $runId}) "
          + "ON CREATE SET s.adapter = $adapter, s.startedAt = $endedAt "
          + "WITH s WHERE s.status IS NULL OR s.status <> 'SUCCEEDED' "
          + "SET s.status = $status, "
          + "s.endedAt = CASE WHEN s.startedAt > $endedAt THEN s.startedAt ELSE $endedAt END, "
          + "s.fetched = $fetched, s.applied = $applied, s.failed = $failed";

  private final Driver driver;

  public SyncRunWriter(Driver driver) {
    this.driver = Objects.requireNonNull(driver, "driver");
  }

  /**
   * Закрывает прогон.
   *
   * @param status {@code SUCCEEDED} или {@code FAILED}
   * @throws IllegalArgumentException если {@code status} — {@code RUNNING}
   */
  public void close(SourceSystemCode adapter, String runId, SyncRunStatus status, Instant endedAt, SyncRunCounts counts) {
    if (status == SyncRunStatus.RUNNING) {
      throw new IllegalArgumentException("a run is closed with a terminal status");
    }
    Map<String, Object> params =
        Map.of(
            "runId", runId,
            "adapter", adapter.name(),
            "status", status.name(),
            "endedAt", NodeProperties.utc(endedAt),
            "fetched", counts.fetched(),
            "applied", counts.applied(),
            "failed", counts.failed());
    try (Session session = driver.session()) {
      session.executeWrite(tx -> tx.run(CLOSE, params).consume());
    }
  }
}
