package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalQuery;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
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

  /** Статус и updated_at выставляются напрямую: проверяется только выборка, а не переходы. */
  private static void setState(String source, String eventId, String status, Instant updatedAt) throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("update inbox_event set status = ?, updated_at = ? where source = ? and event_id = ?")) {
      ps.setString(1, status);
      ps.setTimestamp(2, java.sql.Timestamp.from(updatedAt));
      ps.setString(3, source);
      ps.setString(4, eventId);
      ps.executeUpdate();
    }
  }

  @Test
  void pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset() throws Exception {
    var journal = new PostgresEventJournal(dataSource());
    String src = "urn:corp:pending-" + uid();
    Instant now = Instant.now();
    for (String id : new String[] {"a", "b", "c", "d", "e"}) {
      journal.append(event(src, id, "1"), null, null);
    }
    journal.transition(src, "a", ProcessingStatus.VALIDATED, null, null);
    journal.transition(src, "a", ProcessingStatus.NORMALIZED, null, null);
    journal.transition(src, "a", ProcessingStatus.RESOLVED, null, null);
    journal.transition(src, "a", ProcessingStatus.PROJECTED, null, null);
    // b: свежий RETRYING (не берётся), c: созревший RETRYING (берётся), d: давно прерванный VALIDATED (берётся)
    setState(src, "b", "RETRYING", now);
    setState(src, "c", "RETRYING", now.minusSeconds(120));
    setState(src, "d", "VALIDATED", now.minusSeconds(120));
    Instant retryNotAfter = now.minusSeconds(60);

    var all = journal.pending(null, retryNotAfter, 1000).stream()
        .filter(s -> s.entry().source().equals(src)).map(s -> s.entry().eventId()).toList();
    assertThat(all).containsExactly("c", "d", "e");

    // keyset со страницей 1: каждая строка ровно один раз, без повторов и пропусков
    var seen = new java.util.ArrayList<String>();
    JournalKey after = null;
    for (var page = journal.pending(after, retryNotAfter, 1); !page.isEmpty(); page = journal.pending(after, retryNotAfter, 1)) {
      var entry = page.get(0).entry();
      after = JournalKey.of(entry);
      if (entry.source().equals(src)) {
        seen.add(entry.eventId());
      }
    }
    assertThat(seen).containsExactly("c", "d", "e");
    assertThatThrownBy(() -> journal.pending(null, retryNotAfter, 0)).isInstanceOf(IllegalArgumentException.class);
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

  @Test
  void readerPagesByKeysetInReceivedOrderAndFilters() throws Exception {
    var journal = new PostgresEventJournal(dataSource());
    String source = "urn:corp:reader-" + uid();
    String other = "urn:corp:reader-other-" + uid();
    for (int i = 0; i < 5; i++) {
      journal.append(event(source, "e" + i, "1"), null, null);
    }
    journal.append(event(source, "replay:r1:e0", "1"), null, null);
    journal.append(event(other, "x", "1"), null, null);

    var all = new java.util.ArrayList<StoredEvent>();
    JournalQuery q = new JournalQuery(source, null, null, true, null, 2);
    for (var page = journal.read(q); !page.isEmpty(); page = journal.read(q)) {
      assertThat(page).hasSizeLessThanOrEqualTo(2);
      all.addAll(page);
      q = q.after(JournalKey.of(page.get(page.size() - 1).entry()));
    }

    assertThat(all).extracting(e -> e.entry().eventId()).containsExactly("e0", "e1", "e2", "e3", "e4");
    assertThat(all).allSatisfy(e -> {
      assertThat(e.type()).isEqualTo(CanonicalEvent.TYPE_ASSET_UPSERTED);
      assertThat(e.subject()).isEqualTo("it-system/EAM-1");
    });

    var withReplays = journal.read(new JournalQuery(source, null, null, false, null, 100));
    assertThat(withReplays).extracting(e -> e.entry().eventId()).contains("replay:r1:e0").hasSize(6);

    JournalEntry third = all.get(2).entry();
    var window = journal.read(new JournalQuery(source, third.receivedAt(), third.receivedAt().plusNanos(1000), true, null, 100));
    assertThat(window).extracting(e -> e.entry().eventId()).contains("e2").doesNotContain("e0");
    assertThat(journal.read(new JournalQuery(source, null, all.get(0).entry().receivedAt(), true, null, 100))).isEmpty();
  }

  @Test
  void readerRejectsBadLimit() {
    assertThatThrownBy(() -> new JournalQuery(null, null, null, true, null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JournalQuery(null, null, null, true, null, JournalQuery.MAX_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
