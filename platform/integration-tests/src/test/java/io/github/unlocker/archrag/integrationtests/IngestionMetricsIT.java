package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.ingestionservice.IngestionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.DriverManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** SQL-gauges {@link IngestionMetrics} на реальном PostgreSQL: lag по нетерминальным статусам и открытый DLQ. */
@Testcontainers
class IngestionMetricsIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  private static DataSource dataSource() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  @BeforeAll
  static void migrate() {
    JournalMigrations.apply(dataSource());
  }

  @Test
  void lagAndOpenDlqPerSourceFromJournal() throws Exception {
    // eam: два ожидающих события (старейшее 120 с) и терминальное PROJECTED, которое в lag не входит.
    insert("urn:corp:eam", "e1", "RECEIVED", 120);
    insert("urn:corp:eam", "e2", "RETRYING", 30);
    insert("urn:corp:eam", "e3", "PROJECTED", 900);
    // scm: только терминальные статусы и QUARANTINED: lag 0.
    insert("urn:corp:scm", "s1", "PROJECTED", 500);
    insert("urn:corp:scm", "s2", "QUARANTINED", 400);
    // DLQ: у scm одна открытая запись и одна уже переигранная.
    dlq("urn:corp:scm", "s2", false);
    insert("urn:corp:scm", "s3", "QUARANTINED", 300);
    dlq("urn:corp:scm", "s3", true);

    var registry = new SimpleMeterRegistry();
    new IngestionMetrics(registry, new JdbcTemplate(dataSource())).refresh();

    assertThat(gauge(registry, "archrag.ingestion.sync.lag", "eam")).isBetween(119d, 180d);
    assertThat(gauge(registry, "archrag.ingestion.sync.lag", "scm")).isZero();
    assertThat(gauge(registry, "archrag.ingestion.sync.lag", "cmdb")).isZero();
    assertThat(gauge(registry, "archrag.ingestion.dlq.open", "scm")).isEqualTo(1d);
    assertThat(gauge(registry, "archrag.ingestion.dlq.open", "eam")).isZero();
    assertThat(gauge(registry, "archrag.ingestion.dlq.open", "deploymap")).isZero();
  }

  private static double gauge(SimpleMeterRegistry registry, String name, String source) {
    return registry.get(name).tag("source", source).gauge().value();
  }

  private static void insert(String source, String eventId, String status, int ageSeconds) throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("""
            insert into inbox_event (source, event_id, type, source_type, source_id, source_version, schema_version,
                                     status, received_at, updated_at)
            values (?, ?, 'asset.upserted', 'IT_SYSTEM', ?, '1', 's', ?,
                    now() - make_interval(secs => ?), now())""")) {
      ps.setString(1, source);
      ps.setString(2, eventId);
      ps.setString(3, "obj-" + eventId);
      ps.setString(4, status);
      ps.setInt(5, ageSeconds);
      ps.executeUpdate();
    }
  }

  private static void dlq(String source, String eventId, boolean replayed) throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("""
            insert into dlq_entry (source, event_id, reason, error_code, created_at, replayed_at)
            values (?, ?, 'r', 'C', now(), case when ? then now() end)""")) {
      ps.setString(1, source);
      ps.setString(2, eventId);
      ps.setBoolean(3, replayed);
      ps.executeUpdate();
    }
  }
}
