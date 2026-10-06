package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link SourceConflicts} на PostgreSQL (JDBC без ORM); схема из {@code V5__source_conflict.sql}.
 *
 * <p>Каждый вызов — одна транзакция; пересчёт конфликтов {@code gid} идёт под advisory-lock'ом на {@code gid},
 * иначе две параллельные записи разных источников не увидели бы утверждения друг друга. Правило пересчёта:
 * для каждого свойства с авторитетным утверждением каждое неавторитетное с другим значением — открытый
 * конфликт (upsert, повторное открытие сохраняет {@code opened_at}); пара, которая перестала расходиться,
 * получает {@code RESOLVED} и {@code resolved_at}; без авторитетного утверждения конфликтов нет.
 *
 * <p>Replay и rebuild: таблицы идемпотентны, но промежуточные события могут видеть «будущие» утверждения
 * других записей; после последнего события {@code gid} состояние совпадает с исходным.
 */
public final class PostgresSourceConflicts implements SourceConflicts {

  private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
  private static final String DELETE_ASSERTIONS =
      "DELETE FROM property_assertion WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String INSERT_ASSERTION =
      "INSERT INTO property_assertion (source, source_type, source_id, property, gid, label, value,"
          + " authoritative, asserted_at) VALUES (?,?,?,?,?,?,?,?,?)";
  // Расходящиеся пары: у свойства берётся мастер-утверждение (в PoC один мастер на свойство; при нескольких
  // выбирается детерминированно по ключу записи).
  private static final String DIVERGING =
      "SELECT d.property, d.source, d.source_type, d.source_id, d.value, m.value, m.source, m.source_type, m.source_id"
          + " FROM property_assertion d JOIN LATERAL (SELECT * FROM property_assertion a WHERE a.gid = d.gid"
          + " AND a.property = d.property AND a.authoritative ORDER BY a.source, a.source_type, a.source_id LIMIT 1) m ON true"
          + " WHERE d.gid = ? AND NOT d.authoritative AND d.value <> m.value";
  private static final String UPSERT_CONFLICT =
      "INSERT INTO source_conflict (gid, property, dissent_source, dissent_type, dissent_id, dissent_value,"
          + " master_value, master_source, master_type, master_id, status, opened_at, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,'OPEN',?,?)"
          + " ON CONFLICT (gid, property, dissent_source, dissent_type, dissent_id) DO UPDATE SET"
          + " dissent_value = EXCLUDED.dissent_value, master_value = EXCLUDED.master_value,"
          + " master_source = EXCLUDED.master_source, master_type = EXCLUDED.master_type,"
          + " master_id = EXCLUDED.master_id, status = 'OPEN', resolved_at = NULL,"
          + " updated_at = CASE WHEN source_conflict.status = 'OPEN' AND source_conflict.dissent_value = EXCLUDED.dissent_value"
          + " AND source_conflict.master_value = EXCLUDED.master_value THEN source_conflict.updated_at"
          + " ELSE EXCLUDED.updated_at END";
  private static final String OPEN_KEYS =
      "SELECT property, dissent_source, dissent_type, dissent_id FROM source_conflict WHERE gid = ? AND status = 'OPEN'";
  private static final String RESOLVE =
      "UPDATE source_conflict SET status = 'RESOLVED', resolved_at = ?, updated_at = ?"
          + " WHERE gid = ? AND property = ? AND dissent_source = ? AND dissent_type = ? AND dissent_id = ?";
  private static final String SELECT_OPEN =
      "SELECT gid, property, dissent_source, dissent_type, dissent_id, dissent_value, master_source, master_type,"
          + " master_id, master_value, status, opened_at, updated_at, resolved_at FROM source_conflict"
          + " WHERE gid = ? AND status = 'OPEN' ORDER BY property, dissent_source, dissent_type, dissent_id";

  private final DataSource dataSource;
  private final AuthorityMatrix matrix;

  public PostgresSourceConflicts(DataSource dataSource, AuthorityMatrix matrix) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.matrix = Objects.requireNonNull(matrix, "matrix");
  }

  @Override
  public List<SourceConflict> assertProperties(SourceKey key, UUID gid, NodeLabel label, Map<String, String> values) {
    return inTransaction(
        "assertProperties failed",
        c -> {
          lock(c, gid);
          deleteAssertions(c, key);
          Timestamp now = Timestamp.from(Instant.now());
          try (PreparedStatement ps = c.prepareStatement(INSERT_ASSERTION)) {
            for (Map.Entry<String, String> value : values.entrySet()) {
              int i = bindKey(ps, 1, key);
              ps.setString(i++, value.getKey());
              ps.setObject(i++, gid);
              ps.setString(i++, label.name());
              ps.setString(i++, value.getValue());
              ps.setBoolean(i++, matrix.isAuthoritative(label, value.getKey(), key.source()));
              ps.setTimestamp(i, now);
              ps.executeUpdate();
            }
          }
          return recompute(c, gid);
        });
  }

  @Override
  public List<SourceConflict> retract(SourceKey key, UUID gid) {
    return inTransaction(
        "retract failed",
        c -> {
          lock(c, gid);
          deleteAssertions(c, key);
          return recompute(c, gid);
        });
  }

  @Override
  public List<SourceConflict> openConflicts(UUID gid) {
    try (Connection c = dataSource.getConnection()) {
      return selectOpen(c, gid);
    } catch (SQLException e) {
      throw new IdentityStoreException("openConflicts failed", e);
    }
  }

  private interface Work {
    List<SourceConflict> run(Connection c) throws SQLException;
  }

  private List<SourceConflict> inTransaction(String failure, Work work) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        List<SourceConflict> result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException(failure, e);
    }
  }

  private static void lock(Connection c, UUID gid) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(LOCK)) {
      ps.setString(1, "conflict|" + gid);
      ps.execute();
    }
  }

  private static void deleteAssertions(Connection c, SourceKey key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(DELETE_ASSERTIONS)) {
      bindKey(ps, 1, key);
      ps.executeUpdate();
    }
  }

  /** Открывает расходящиеся пары, закрывает перестав расходиться; возвращает открытые конфликты {@code gid}. */
  private static List<SourceConflict> recompute(Connection c, UUID gid) throws SQLException {
    Timestamp now = Timestamp.from(Instant.now());
    List<List<String>> diverging = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(DIVERGING)) {
      ps.setObject(1, gid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          List<String> row = new ArrayList<>();
          for (int i = 1; i <= 9; i++) {
            row.add(rs.getString(i));
          }
          diverging.add(row);
        }
      }
    }
    List<List<String>> stillOpen = new ArrayList<>();
    for (List<String> row : diverging) {
      try (PreparedStatement ps = c.prepareStatement(UPSERT_CONFLICT)) {
        int i = 1;
        ps.setObject(i++, gid);
        ps.setString(i++, row.get(0));
        ps.setString(i++, row.get(1));
        ps.setString(i++, row.get(2));
        ps.setString(i++, row.get(3));
        ps.setString(i++, row.get(4));
        ps.setString(i++, row.get(5));
        ps.setString(i++, row.get(6));
        ps.setString(i++, row.get(7));
        ps.setString(i++, row.get(8));
        ps.setTimestamp(i++, now);
        ps.setTimestamp(i, now);
        ps.executeUpdate();
      }
      stillOpen.add(List.of(row.get(0), row.get(1), row.get(2), row.get(3)));
    }
    List<List<String>> toResolve = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(OPEN_KEYS)) {
      ps.setObject(1, gid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          List<String> pair = List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
          if (!stillOpen.contains(pair)) {
            toResolve.add(pair);
          }
        }
      }
    }
    for (List<String> pair : toResolve) {
      try (PreparedStatement ps = c.prepareStatement(RESOLVE)) {
        ps.setTimestamp(1, now);
        ps.setTimestamp(2, now);
        ps.setObject(3, gid);
        for (int i = 0; i < 4; i++) {
          ps.setString(4 + i, pair.get(i));
        }
        ps.executeUpdate();
      }
    }
    return selectOpen(c, gid);
  }

  private static List<SourceConflict> selectOpen(Connection c, UUID gid) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_OPEN)) {
      ps.setObject(1, gid);
      try (ResultSet rs = ps.executeQuery()) {
        List<SourceConflict> result = new ArrayList<>();
        while (rs.next()) {
          Timestamp resolved = rs.getTimestamp(14);
          result.add(
              new SourceConflict(
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  readKey(rs, 3),
                  rs.getString(6),
                  readKey(rs, 7),
                  rs.getString(10),
                  SourceConflict.Status.valueOf(rs.getString(11)),
                  rs.getTimestamp(12).toInstant(),
                  rs.getTimestamp(13).toInstant(),
                  resolved == null ? null : resolved.toInstant()));
        }
        return result;
      }
    }
  }

  private static SourceKey readKey(ResultSet rs, int index) throws SQLException {
    return new SourceKey(SourceSystemCode.valueOf(rs.getString(index)), rs.getString(index + 1), rs.getString(index + 2));
  }

  private static int bindKey(PreparedStatement ps, int index, SourceKey key) throws SQLException {
    ps.setString(index++, key.source().name());
    ps.setString(index++, key.sourceType());
    ps.setString(index++, key.sourceId());
    return index;
  }
}
