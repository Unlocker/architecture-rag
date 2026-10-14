package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link CandidateApprovals} на PostgreSQL (JDBC без ORM); схема из {@code V2}, {@code V4}, {@code V5}, {@code V8}.
 *
 * <p>Одна транзакция на вызов. Кандидат блокируется {@code FOR UPDATE}: два параллельных подтверждения пары не
 * пройдут оба. Пересчёт конфликтов {@code gid} ({@link PostgresSourceConflicts}) идёт под advisory-lock'ом
 * {@code conflict|<gid>}; здесь берутся блокировки обоих {@code gid} в стабильном порядке. {@code source_conflict}
 * прежнего {@code gid} закрывается только по записи, где она расходящаяся или мастер: конфликты остальных записей
 * остаются. Конфликты нового {@code gid} считает перепроекция.
 */
public final class PostgresCandidateApprovals implements CandidateApprovals {

  private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
  private static final String SELECT_STATUS =
      "SELECT status FROM identity_candidate WHERE left_source = ? AND left_type = ? AND left_id = ?"
          + " AND right_source = ? AND right_type = ? AND right_id = ? FOR UPDATE";
  private static final String SELECT_GID =
      "SELECT gid FROM identity_mapping WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String INSERT_MAPPING = "INSERT INTO identity_mapping (source, source_type, source_id, gid,"
      + " created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING";
  private static final String MOVE_MAPPING =
      "UPDATE identity_mapping SET gid = ? WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String MOVE_ASSERTIONS =
      "UPDATE property_assertion SET gid = ? WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String CLOSE_CONFLICTS =
      "UPDATE source_conflict SET status = 'RESOLVED', resolved_at = ?, updated_at = ? WHERE gid = ? AND status = 'OPEN'"
          + " AND ((dissent_source = ? AND dissent_type = ? AND dissent_id = ?)"
          + " OR (master_source = ? AND master_type = ? AND master_id = ?))";
  private static final String INSERT_CROSSWALK = "INSERT INTO approved_crosswalk (left_source, left_type, left_id,"
      + " right_source, right_type, right_id, approved_by, reason, approved_at) VALUES (?,?,?,?,?,?,?,?,?)"
      + " ON CONFLICT DO NOTHING";
  private static final String RESOLVE_CANDIDATE =
      "UPDATE identity_candidate SET status = 'RESOLVED', resolved_at = ?, resolved_by = ? WHERE left_source = ?"
          + " AND left_type = ? AND left_id = ? AND right_source = ? AND right_type = ? AND right_id = ?";
  private static final String GID_IN_USE = "SELECT 1 FROM identity_mapping WHERE gid = ? LIMIT 1";

  private final DataSource dataSource;

  public PostgresCandidateApprovals(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public CandidateRelocation approve(CandidateApproval approval) {
    boolean swap = SourceKeys.compare(approval.left(), approval.right()) > 0;
    SourceKey lo = swap ? approval.right() : approval.left();
    SourceKey hi = swap ? approval.left() : approval.right();
    SourceKey survivor = approval.survivorKey();
    SourceKey moved = approval.movedKey();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        requireOpen(c, lo, hi);
        UUID into = getOrCreate(c, survivor);
        Optional<UUID> from = select(c, moved);
        Timestamp at = Timestamp.from(approval.approvedAt());
        boolean empty = false;
        if (from.isEmpty()) {
          insertMapping(c, moved, into);
        } else if (!from.get().equals(into)) {
          lockGids(c, from.get(), into);
          update(c, MOVE_MAPPING, into, moved);
          update(c, MOVE_ASSERTIONS, into, moved);
          closeConflicts(c, from.get(), moved, at);
          empty = !gidInUse(c, from.get());
        }
        insertCrosswalk(c, lo, hi, approval);
        resolveCandidate(c, lo, hi, approval.approvedBy(), at);
        c.commit();
        return new CandidateRelocation(moved, survivor, from.orElse(into), into, empty);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("approve candidate failed", e);
    }
  }

  private static void requireOpen(Connection c, SourceKey lo, SourceKey hi) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_STATUS)) {
      bindKey(ps, bindKey(ps, 1, lo), hi);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new CandidateNotFoundException("no identity candidate for " + lo + " and " + hi);
        }
        if (!"OPEN".equals(rs.getString(1))) {
          throw new CandidateNotOpenException("identity candidate for " + lo + " and " + hi + " is " + rs.getString(1));
        }
      }
    }
  }

  private static void lockGids(Connection c, UUID a, UUID b) throws SQLException {
    UUID first = a.compareTo(b) <= 0 ? a : b;
    UUID second = first == a ? b : a;
    for (UUID gid : new UUID[] {first, second}) {
      try (PreparedStatement ps = c.prepareStatement(LOCK)) {
        ps.setString(1, "conflict|" + gid);
        ps.execute();
      }
    }
  }

  private static void update(Connection c, String sql, UUID gid, SourceKey key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, gid);
      bindKey(ps, 2, key);
      ps.executeUpdate();
    }
  }

  private static void closeConflicts(Connection c, UUID gid, SourceKey key, Timestamp at) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(CLOSE_CONFLICTS)) {
      ps.setTimestamp(1, at);
      ps.setTimestamp(2, at);
      ps.setObject(3, gid);
      bindKey(ps, bindKey(ps, 4, key), key);
      ps.executeUpdate();
    }
  }

  private static boolean gidInUse(Connection c, UUID gid) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(GID_IN_USE)) {
      ps.setObject(1, gid);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static void insertCrosswalk(Connection c, SourceKey lo, SourceKey hi, CandidateApproval approval)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_CROSSWALK)) {
      int i = bindKey(ps, bindKey(ps, 1, lo), hi);
      ps.setString(i++, approval.approvedBy());
      ps.setString(i++, approval.reason());
      ps.setTimestamp(i, Timestamp.from(approval.approvedAt()));
      ps.executeUpdate();
    }
  }

  private static void resolveCandidate(Connection c, SourceKey lo, SourceKey hi, String by, Timestamp at)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(RESOLVE_CANDIDATE)) {
      ps.setTimestamp(1, at);
      ps.setString(2, by);
      bindKey(ps, bindKey(ps, 3, lo), hi);
      ps.executeUpdate();
    }
  }

  private static UUID getOrCreate(Connection c, SourceKey key) throws SQLException {
    insertMapping(c, key, UUID.randomUUID());
    return select(c, key).orElseThrow(() -> new IdentityStoreException("mapping vanished after insert", null));
  }

  private static void insertMapping(Connection c, SourceKey key, UUID gid) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_MAPPING)) {
      int i = bindKey(ps, 1, key);
      ps.setObject(i++, gid);
      ps.setTimestamp(i, Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }
  }

  private static Optional<UUID> select(Connection c, SourceKey key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_GID)) {
      bindKey(ps, 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
      }
    }
  }

  private static int bindKey(PreparedStatement ps, int index, SourceKey key) throws SQLException {
    ps.setString(index++, key.source().name());
    ps.setString(index++, key.sourceType());
    ps.setString(index++, key.sourceId());
    return index;
  }
}
