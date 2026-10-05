package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.identityresolution.SourceConflict;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Правило пересчёта конфликтов {@link PostgresSourceConflicts} на реальном PostgreSQL (E3.3). */
@Testcontainers
class PostgresSourceConflictsIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static PostgresSourceConflicts store;

  @BeforeAll
  static void setUp() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    store = new PostgresSourceConflicts(ds, AuthorityMatrix.defaults());
  }

  private static SourceKey eam(String id) {
    return new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", id);
  }

  private static SourceKey cmdb(String id) {
    return new SourceKey(SourceSystemCode.CMDB, "IT_SYSTEM", id);
  }

  private static List<SourceConflict> master(SourceKey key, UUID gid, String criticality) {
    return store.assertProperties(key, gid, NodeLabel.IT_SYSTEM, Map.of("criticality", criticality));
  }

  private static List<SourceConflict> dissent(SourceKey key, UUID gid, String criticality) {
    return store.assertProperties(key, gid, NodeLabel.IT_SYSTEM, Map.of("criticality", criticality, "name", "n"));
  }

  private static Map<String, Object> row(UUID gid, SourceKey dissent) throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("select status, opened_at, resolved_at, updated_at, dissent_value, master_value"
            + " from source_conflict where gid = ? and dissent_id = ?")) {
      ps.setObject(1, gid);
      ps.setString(2, dissent.sourceId());
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        var m = new java.util.HashMap<String, Object>();
        m.put("status", rs.getString(1));
        m.put("openedAt", rs.getTimestamp(2).toInstant());
        m.put("resolvedAt", rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant());
        m.put("updatedAt", rs.getTimestamp(4).toInstant());
        m.put("dissentValue", rs.getString(5));
        m.put("masterValue", rs.getString(6));
        return m;
      }
    }
  }

  @Test
  void differingNonAuthoritativeValueOpensOneConflict() {
    UUID gid = UUID.randomUUID();
    var e = eam("s-" + gid);
    var c = cmdb("s-" + gid);

    master(e, gid, "HIGH");
    var open = dissent(c, gid, "LOW");

    assertThat(open).singleElement().satisfies(x -> {
      assertThat(x.property()).isEqualTo("criticality");
      assertThat(x.dissent()).isEqualTo(c);
      assertThat(x.master()).isEqualTo(e);
      assertThat(x.dissentValue()).isEqualTo("LOW");
      assertThat(x.masterValue()).isEqualTo("HIGH");
      assertThat(x.status()).isEqualTo(SourceConflict.Status.OPEN);
    });
    assertThat(store.openConflicts(gid)).hasSize(1);
  }

  @Test
  void equalValueResolvesAndReopeningKeepsOpenedAt() throws SQLException {
    UUID gid = UUID.randomUUID();
    var e = eam("s-" + gid);
    var c = cmdb("s-" + gid);
    master(e, gid, "HIGH");
    dissent(c, gid, "LOW");
    Instant opened = (Instant) row(gid, c).get("openedAt");

    var afterAgree = dissent(c, gid, "HIGH");

    assertThat(afterAgree).isEmpty();
    assertThat(row(gid, c)).containsEntry("status", "RESOLVED").doesNotContainEntry("resolvedAt", null);

    var reopened = dissent(c, gid, "LOW");

    assertThat(reopened).hasSize(1);
    assertThat(row(gid, c)).containsEntry("status", "OPEN").containsEntry("resolvedAt", null).containsEntry("openedAt", opened);
  }

  @Test
  void conflictDoesNotDependOnArrivalOrder() {
    UUID gid = UUID.randomUUID();

    assertThat(dissent(cmdb("s-" + gid), gid, "LOW")).as("nothing to compare with").isEmpty();
    var open = master(eam("s-" + gid), gid, "HIGH");

    assertThat(open).singleElement().satisfies(x -> {
      assertThat(x.dissentValue()).isEqualTo("LOW");
      assertThat(x.masterValue()).isEqualTo("HIGH");
    });
  }

  @Test
  void masterChangingValueRecomputesPair() {
    UUID gid = UUID.randomUUID();
    master(eam("s-" + gid), gid, "HIGH");
    dissent(cmdb("s-" + gid), gid, "LOW");

    assertThat(master(eam("s-" + gid), gid, "LOW")).isEmpty();
    assertThat(master(eam("s-" + gid), gid, "MEDIUM")).singleElement().satisfies(x -> {
      assertThat(x.masterValue()).isEqualTo("MEDIUM");
      assertThat(x.dissentValue()).isEqualTo("LOW");
    });
  }

  @Test
  void onlyNonAuthoritativeAssertionsGiveNoConflict() {
    UUID gid = UUID.randomUUID();

    assertThat(dissent(cmdb("a-" + gid), gid, "LOW")).isEmpty();
    assertThat(dissent(cmdb("b-" + gid), gid, "HIGH")).isEmpty();
    assertThat(store.openConflicts(gid)).isEmpty();
  }

  @Test
  void retractOfDissentResolvesAndRetractOfMasterResolvesToo() throws SQLException {
    UUID gid = UUID.randomUUID();
    var e = eam("s-" + gid);
    var c = cmdb("s-" + gid);
    master(e, gid, "HIGH");
    dissent(c, gid, "LOW");

    assertThat(store.retract(c, gid)).isEmpty();
    assertThat(row(gid, c)).containsEntry("status", "RESOLVED");

    dissent(c, gid, "LOW");
    assertThat(store.retract(e, gid)).isEmpty();
    assertThat(row(gid, c)).containsEntry("status", "RESOLVED");
  }

  @Test
  void repeatedCallLeavesStateUnchanged() throws SQLException {
    UUID gid = UUID.randomUUID();
    var c = cmdb("s-" + gid);
    master(eam("s-" + gid), gid, "HIGH");
    var first = dissent(c, gid, "LOW");
    var before = row(gid, c);

    var second = dissent(c, gid, "LOW");

    assertThat(second).isEqualTo(first);
    assertThat(row(gid, c)).isEqualTo(before);
  }

  @Test
  void conflictsOfOtherGidAreNotAffected() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    master(eam("s-" + a), a, "HIGH");
    dissent(cmdb("s-" + a), a, "LOW");

    master(eam("s-" + b), b, "LOW");

    assertThat(store.openConflicts(a)).hasSize(1);
    assertThat(store.openConflicts(b)).isEmpty();
  }
}
