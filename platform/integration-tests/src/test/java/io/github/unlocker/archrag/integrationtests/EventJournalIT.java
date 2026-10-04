package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Журнал на реальных PostgreSQL и S3 (SeaweedFS): дедупликация, статусы, DLQ, checkpoint, raw payload. */
@Testcontainers
class EventJournalIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  @BeforeAll
  static void migrate() {
    JournalMigrations.apply(dataSource());
  }

  private static DataSource dataSource() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  private static CanonicalEvent event(String source, String id, String version) {
    return new CanonicalEvent(id, source, CanonicalEvent.TYPE_ASSET_UPSERTED, "it-system/EAM-1",
        Instant.parse("2026-09-30T17:20:00Z"), "urn:corp:schema:asset-upserted:1", "corr-1",
        new AssetEventData("IT_SYSTEM", "EAM-1", new SourceVersion(version), Map.of("name", "x")));
  }

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static int rows(String source, String eventId) throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("select count(*) from inbox_event where source = ? and event_id = ?")) {
      ps.setString(1, source);
      ps.setString(2, eventId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static java.util.List<String> dlqRows(String source, String eventId) {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement(
            "select reason, error_code, payload_key from dlq_entry where source = ? and event_id = ?")) {
      ps.setString(1, source);
      ps.setString(2, eventId);
      var rows = new java.util.ArrayList<String>();
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        }
      }
      return rows;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void duplicateEventIsNotInsertedTwice() throws Exception {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    var first = journal.append(event("urn:corp:eam", id, "5"), null, "run-1");
    var second = journal.append(event("urn:corp:eam", id, "6"), null, "run-2");

    assertThat(first.status()).isEqualTo(ProcessingStatus.RECEIVED);
    assertThat(second.status()).isEqualTo(ProcessingStatus.DUPLICATE);
    assertThat(second.sourceVersion().value()).isEqualTo("5");
    assertThat(rows("urn:corp:eam", id)).isEqualTo(1);
    assertThat(journal.find("urn:corp:eam", id).orElseThrow().status()).isEqualTo(ProcessingStatus.RECEIVED);
    assertThat(journal.publish(event("urn:corp:eam", id, "5"), null, null)).isFalse();
  }

  @Test
  void sameEventIdFromAnotherSourceIsAccepted() throws Exception {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    journal.append(event("urn:corp:eam", id, "1"), null, null);
    var other = journal.append(event("urn:corp:scm", id, "1"), null, null);

    assertThat(other.status()).isEqualTo(ProcessingStatus.RECEIVED);
  }

  @Test
  void transitionsAndDlqArePersisted() {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    journal.append(event("urn:corp:eam", id, "1"), new RawPayloadRef("raw/k", "h"), null);

    var retry = journal.transition("urn:corp:eam", id, ProcessingStatus.RETRYING, "NEO4J_TRANSIENT", "timeout");
    assertThat(retry.attempts()).isEqualTo(1);
    var dlq = journal.toDlq("urn:corp:eam", id, "SCHEMA_UNKNOWN", "unknown schema");
    assertThat(dlq.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(dlq.errorCode()).isEqualTo("SCHEMA_UNKNOWN");
    assertThat(dlq.payloadRef().key()).isEqualTo("raw/k");
    assertThat(dlqRows("urn:corp:eam", id)).containsExactly("unknown schema|SCHEMA_UNKNOWN|raw/k");
    assertThatThrownBy(() -> journal.transition("urn:corp:eam", "missing", ProcessingStatus.PROJECTED, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repeatedDlqKeepsSingleRecord() {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    journal.append(event("urn:corp:eam", id, "1"), null, null);

    journal.toDlq("urn:corp:eam", id, "SCHEMA_UNKNOWN", "first");
    journal.toDlq("urn:corp:eam", id, "SCHEMA_UNKNOWN", "second");

    assertThat(dlqRows("urn:corp:eam", id)).hasSize(1);
  }

  @Test
  void dlqRejectsBlankCodeAndUnknownEvent() {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    journal.append(event("urn:corp:eam", id, "1"), null, null);

    assertThatThrownBy(() -> journal.toDlq("urn:corp:eam", id, " ", "r")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> journal.toDlq("urn:corp:eam", "missing", "CODE", "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(dlqRows("urn:corp:eam", id)).isEmpty();
  }

  @Test
  void finalStateCannotBeRolledBack() {
    var journal = new PostgresEventJournal(dataSource());
    String id = uid();
    journal.append(event("urn:corp:eam", id, "1"), null, null);
    journal.transition("urn:corp:eam", id, ProcessingStatus.VALIDATED, null, null);
    journal.transition("urn:corp:eam", id, ProcessingStatus.NORMALIZED, null, null);
    journal.transition("urn:corp:eam", id, ProcessingStatus.RESOLVED, null, null);
    journal.transition("urn:corp:eam", id, ProcessingStatus.PROJECTED, null, null);

    assertThatThrownBy(() -> journal.transition("urn:corp:eam", id, ProcessingStatus.RETRYING, "LATE", "late worker"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(journal.find("urn:corp:eam", id).orElseThrow().status()).isEqualTo(ProcessingStatus.PROJECTED);
  }

  @Test
  void checkpointSurvivesJournalRestart() {
    String source = "urn:corp:" + uid();
    new PostgresEventJournal(dataSource()).saveCheckpoint("projector", source, "cursor-1");
    new PostgresEventJournal(dataSource()).saveCheckpoint("projector", source, "cursor-2");

    var restarted = new PostgresEventJournal(dataSource());
    assertThat(restarted.loadCheckpoint("projector", source).orElseThrow().cursor()).isEqualTo("cursor-2");
    assertThat(restarted.loadCheckpoint("other", source)).isEmpty();
  }

  @Test
  void migrationsAreIdempotent() {
    JournalMigrations.apply(dataSource());
    JournalMigrations.apply(dataSource());
  }

  @Test
  void rawPayloadRoundTripsAndKeyIsContentAddressed() {
    try (var store = S3RawPayloadStore.create(
        URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
        ContainersSmokeIT.S3_ACCESS_KEY, ContainersSmokeIT.S3_SECRET_KEY, "raw-bucket")) {
      store.ensureBucket();
      byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
      var ref = store.put("urn:corp:eam", body);
      var again = store.put("urn:corp:eam", body);

      assertThat(again).isEqualTo(ref);
      assertThat(ref.key()).isEqualTo("raw/urn:corp:eam/" + ref.contentHash());
      assertThat(store.get(ref)).isEqualTo(body);
      assertThatThrownBy(() -> store.get(new RawPayloadRef(ref.key(), "0".repeat(64))))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
