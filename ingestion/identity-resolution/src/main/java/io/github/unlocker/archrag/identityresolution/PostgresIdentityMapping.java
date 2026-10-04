package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link IdentityMapping} на PostgreSQL (JDBC без ORM); схема из {@code V2__identity_mapping.sql}.
 *
 * <p>Каждая операция — одна транзакция. Создание {@code gid} — {@code INSERT ... ON CONFLICT DO
 * NOTHING} с последующим чтением, поэтому параллельные вызовы сходятся к одному значению.
 */
public final class PostgresIdentityMapping implements IdentityMapping {

  private static final String SELECT_GID =
      "SELECT gid FROM identity_mapping WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String INSERT_MAPPING = "INSERT INTO identity_mapping (source, source_type, source_id, gid,"
      + " created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING";

  private final DataSource dataSource;

  public PostgresIdentityMapping(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Optional<UUID> find(SourceKey key) {
    try (Connection c = dataSource.getConnection()) {
      return select(c, key);
    } catch (SQLException e) {
      throw new IdentityStoreException("find failed", e);
    }
  }

  @Override
  public UUID resolve(SourceKey key) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        UUID gid = getOrCreate(c, key, UUID.randomUUID());
        c.commit();
        return gid;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("resolve failed", e);
    }
  }

  @Override
  public List<SourceKey> keysOf(UUID gid) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT source, source_type, source_id FROM identity_mapping"
            + " WHERE gid = ? ORDER BY source, source_type, source_id")) {
      ps.setObject(1, gid);
      try (ResultSet rs = ps.executeQuery()) {
        List<SourceKey> keys = new ArrayList<>();
        while (rs.next()) {
          keys.add(new SourceKey(SourceSystemCode.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3)));
        }
        return keys;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("keysOf failed", e);
    }
  }

  @Override
  public UUID approve(Crosswalk crosswalk) {
    SourceKey left = crosswalk.left();
    SourceKey right = crosswalk.right();
    boolean swap = compare(left, right) > 0;
    SourceKey lo = swap ? right : left;
    SourceKey hi = swap ? left : right;
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        UUID gid = link(c, lo, hi);
        insertCrosswalk(c, lo, hi, crosswalk);
        c.commit();
        return gid;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("approve failed", e);
    }
  }

  /** Даёт обоим ключам один gid; расхождение уже назначенных gid — конфликт, а не молчаливый выбор. */
  private UUID link(Connection c, SourceKey a, SourceKey b) throws SQLException {
    Optional<UUID> ga = select(c, a);
    Optional<UUID> gb = select(c, b);
    if (ga.isPresent() && gb.isPresent() && !ga.get().equals(gb.get())) {
      throw conflict(a, b);
    }
    UUID gid = ga.or(() -> gb).orElseGet(UUID::randomUUID);
    UUID gidA = getOrCreate(c, a, gid);
    UUID gidB = getOrCreate(c, b, gid);
    if (!gidA.equals(gidB)) {
      // Параллельный resolve успел назначить другой gid между чтением и вставкой.
      throw conflict(a, b);
    }
    return gidA;
  }

  private static CrosswalkConflictException conflict(SourceKey a, SourceKey b) {
    return new CrosswalkConflictException("keys are already mapped to different gids: " + a + " and " + b);
  }

  private UUID getOrCreate(Connection c, SourceKey key, UUID candidate) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_MAPPING)) {
      int i = bindKey(ps, 1, key);
      ps.setObject(i++, candidate);
      ps.setTimestamp(i, Timestamp.from(Instant.now()));
      ps.executeUpdate();
    }
    return select(c, key).orElseThrow(() -> new IdentityStoreException("mapping vanished after insert", null));
  }

  private static void insertCrosswalk(Connection c, SourceKey lo, SourceKey hi, Crosswalk cw) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("INSERT INTO approved_crosswalk (left_source, left_type, left_id,"
        + " right_source, right_type, right_id, approved_by, reason, approved_at) VALUES (?,?,?,?,?,?,?,?,?)"
        + " ON CONFLICT DO NOTHING")) {
      int i = bindKey(ps, 1, lo);
      i = bindKey(ps, i, hi);
      ps.setString(i++, cw.approvedBy());
      ps.setString(i++, cw.reason());
      ps.setTimestamp(i, Timestamp.from(cw.approvedAt()));
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

  /** Нормализованный порядок пары (его держит только приложение): по (source, sourceType, sourceId) в лексикографическом порядке строк. */
  private static int compare(SourceKey a, SourceKey b) {
    int r = a.source().name().compareTo(b.source().name());
    if (r == 0) {
      r = a.sourceType().compareTo(b.sourceType());
    }
    return r == 0 ? a.sourceId().compareTo(b.sourceId()) : r;
  }
}
