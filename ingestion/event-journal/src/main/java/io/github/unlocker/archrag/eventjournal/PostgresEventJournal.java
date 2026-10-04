package io.github.unlocker.archrag.eventjournal;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.CanonicalEventPublisher;
import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@link EventJournal} и {@link CanonicalEventPublisher} на PostgreSQL (JDBC без ORM).
 *
 * <p>Каждая операция — одна транзакция. Дубль {@code (source, eventId)} решается
 * {@code INSERT ... ON CONFLICT DO NOTHING} и не является исключением. SQL-ошибки пробрасываются
 * как {@link JournalException}. Время хранится в UTC ({@code timestamptz}).
 */
public final class PostgresEventJournal implements EventJournal, CanonicalEventPublisher {

  private static final int MAX_REASON_LENGTH = 500;

  private static final String COLUMNS = "source, event_id, correlation_id, sync_run_id, source_type, source_id,"
      + " source_version, schema_version, status, error_code, error_reason, attempts, payload_key, payload_hash,"
      + " received_at, updated_at";

  private final DataSource dataSource;

  /** Схема должна быть применена через {@link JournalMigrations#apply}. */
  public PostgresEventJournal(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public JournalEntry append(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId) {
    Instant now = Instant.now();
    String insert = "INSERT INTO inbox_event (source, event_id, type, subject, source_type, source_id,"
        + " source_version, correlation_id, sync_run_id, schema_version, status, attempts, payload_key,"
        + " payload_hash, received_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)"
        + " ON CONFLICT (source, event_id) DO NOTHING RETURNING " + COLUMNS;
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement(insert)) {
        int i = 1;
        ps.setString(i++, event.source());
        ps.setString(i++, event.id());
        ps.setString(i++, event.type());
        ps.setString(i++, event.subject());
        ps.setString(i++, event.data().sourceType());
        ps.setString(i++, event.data().sourceId());
        ps.setString(i++, event.data().sourceVersion().value());
        ps.setString(i++, event.correlationid());
        ps.setString(i++, syncRunId);
        ps.setString(i++, event.dataschema());
        ps.setString(i++, ProcessingStatus.RECEIVED.name());
        ps.setString(i++, payloadRef == null ? null : payloadRef.key());
        ps.setString(i++, payloadRef == null ? null : payloadRef.contentHash());
        ps.setTimestamp(i++, Timestamp.from(now));
        ps.setTimestamp(i, Timestamp.from(now));
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            JournalEntry created = map(rs);
            c.commit();
            return created;
          }
        }
        JournalEntry existing = select(c, event.source(), event.id())
            .orElseThrow(() -> new JournalException("inbox row vanished after conflict", null));
        c.commit();
        return withStatus(existing, ProcessingStatus.DUPLICATE);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new JournalException("append failed", e);
    }
  }

  @Override
  public boolean publish(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId) {
    return append(event, payloadRef, syncRunId).status() != ProcessingStatus.DUPLICATE;
  }

  @Override
  public Optional<JournalEntry> find(String source, String eventId) {
    try (Connection c = dataSource.getConnection()) {
      return select(c, source, eventId);
    } catch (SQLException e) {
      throw new JournalException("find failed", e);
    }
  }

  @Override
  public JournalEntry transition(String source, String eventId, ProcessingStatus status, String errorCode,
      String errorReason) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        JournalEntry entry = update(c, source, eventId, status, errorCode, errorReason);
        c.commit();
        return entry;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new JournalException("transition failed", e);
    }
  }

  @Override
  public JournalEntry toDlq(String source, String eventId, String errorCode, String reason) {
    if (errorCode == null || errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode is required");
    }
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        JournalEntry entry = update(c, source, eventId, ProcessingStatus.QUARANTINED, errorCode, reason);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dlq_entry (source, event_id, reason,"
            + " error_code, payload_key, created_at) VALUES (?,?,?,?,?,?)")) {
          ps.setString(1, source);
          ps.setString(2, eventId);
          ps.setString(3, truncate(reason == null ? errorCode : reason));
          ps.setString(4, errorCode);
          ps.setString(5, entry.payloadRef() == null ? null : entry.payloadRef().key());
          ps.setTimestamp(6, Timestamp.from(Instant.now()));
          ps.executeUpdate();
        }
        c.commit();
        return entry;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new JournalException("toDlq failed", e);
    }
  }

  @Override
  public Optional<Checkpoint> loadCheckpoint(String consumer, String source) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT cursor, updated_at FROM consumer_checkpoint WHERE consumer = ? AND source = ?")) {
      ps.setString(1, consumer);
      ps.setString(2, source);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? Optional.of(new Checkpoint(consumer, source, rs.getString(1), rs.getTimestamp(2).toInstant()))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new JournalException("loadCheckpoint failed", e);
    }
  }

  @Override
  public Checkpoint saveCheckpoint(String consumer, String source, String cursor) {
    Instant now = Instant.now();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("INSERT INTO consumer_checkpoint (consumer, source, cursor,"
            + " updated_at) VALUES (?,?,?,?) ON CONFLICT (consumer, source) DO UPDATE SET cursor = EXCLUDED.cursor,"
            + " updated_at = EXCLUDED.updated_at")) {
      ps.setString(1, consumer);
      ps.setString(2, source);
      ps.setString(3, cursor);
      ps.setTimestamp(4, Timestamp.from(now));
      ps.executeUpdate();
      return new Checkpoint(consumer, source, cursor, now);
    } catch (SQLException e) {
      throw new JournalException("saveCheckpoint failed", e);
    }
  }

  private static JournalEntry update(Connection c, String source, String eventId, ProcessingStatus status,
      String errorCode, String errorReason) throws SQLException {
    String sql = "UPDATE inbox_event SET status = ?, error_code = ?, error_reason = ?,"
        + " attempts = attempts + ?, updated_at = ? WHERE source = ? AND event_id = ? RETURNING " + COLUMNS;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, status.name());
      ps.setString(2, errorCode);
      ps.setString(3, errorReason == null ? null : truncate(errorReason));
      ps.setInt(4, status == ProcessingStatus.RETRYING ? 1 : 0);
      ps.setTimestamp(5, Timestamp.from(Instant.now()));
      ps.setString(6, source);
      ps.setString(7, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalArgumentException("unknown event " + source + "/" + eventId);
        }
        return map(rs);
      }
    }
  }

  private static Optional<JournalEntry> select(Connection c, String source, String eventId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT " + COLUMNS + " FROM inbox_event WHERE source = ? AND event_id = ?")) {
      ps.setString(1, source);
      ps.setString(2, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private static JournalEntry map(ResultSet rs) throws SQLException {
    String key = rs.getString("payload_key");
    RawPayloadRef ref = key == null ? null : new RawPayloadRef(key, rs.getString("payload_hash"));
    return new JournalEntry(
        rs.getString("source"),
        rs.getString("event_id"),
        rs.getString("correlation_id"),
        rs.getString("sync_run_id"),
        rs.getString("source_type"),
        rs.getString("source_id"),
        new SourceVersion(rs.getString("source_version")),
        rs.getString("schema_version"),
        ProcessingStatus.valueOf(rs.getString("status")),
        rs.getString("error_code"),
        rs.getString("error_reason"),
        rs.getInt("attempts"),
        ref,
        rs.getTimestamp("received_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static JournalEntry withStatus(JournalEntry e, ProcessingStatus status) {
    return new JournalEntry(e.source(), e.eventId(), e.correlationId(), e.syncRunId(), e.sourceType(), e.sourceId(),
        e.sourceVersion(), e.schemaVersion(), status, e.errorCode(), e.errorReason(), e.attempts(), e.payloadRef(),
        e.receivedAt(), e.updatedAt());
  }

  /** Причина приходит из недоверенного ввода: хранится усечённой. */
  private static String truncate(String s) {
    return s.length() <= MAX_REASON_LENGTH ? s : s.substring(0, MAX_REASON_LENGTH);
  }
}
