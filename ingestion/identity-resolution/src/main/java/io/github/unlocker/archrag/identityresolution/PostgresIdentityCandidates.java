package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link IdentityCandidates} на PostgreSQL (JDBC без ORM); схема из {@code V4__identity_candidate.sql}.
 *
 * <p>{@code observe} — одна транзакция. Текущий {@code gid} другой записи берётся из
 * {@code identity_mapping}, поэтому записи, объединённые crosswalk, кандидатов не дают. Число пар на
 * одно наблюдение ограничено {@value #MAX_PAIRS}: остаток отбрасывается с предупреждением в журнале
 * (значения признаков в журнал не попадают).
 */
public final class PostgresIdentityCandidates implements IdentityCandidates {

  /** Предел числа пар, которые создаёт или обновляет одно наблюдение. */
  public static final int MAX_PAIRS = 50;

  private static final System.Logger LOG = System.getLogger(PostgresIdentityCandidates.class.getName());

  private static final String UPSERT_FEATURE =
      "INSERT INTO identity_feature (source, source_type, source_id, gid, label, feature, value, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (source, source_type, source_id, feature) DO UPDATE SET"
          + " gid = EXCLUDED.gid, label = EXCLUDED.label, value = EXCLUDED.value, updated_at = EXCLUDED.updated_at";
  private static final String DELETE_STALE =
      "DELETE FROM identity_feature WHERE source = ? AND source_type = ? AND source_id = ? AND feature <> ALL (?)";
  private static final String FIND_OTHERS =
      "SELECT DISTINCT m.gid FROM identity_feature f JOIN identity_mapping m ON m.source = f.source"
          + " AND m.source_type = f.source_type AND m.source_id = f.source_id"
          + " WHERE f.label = ? AND f.feature = ? AND f.value = ? AND m.gid <> ? ORDER BY m.gid LIMIT ?";
  private static final String FIND_OTHERS_AMONG =
      "SELECT DISTINCT m.gid FROM identity_feature f JOIN identity_mapping m ON m.source = f.source"
          + " AND m.source_type = f.source_type AND m.source_id = f.source_id"
          + " WHERE f.label = ? AND f.feature = ? AND f.value = ? AND m.gid = ANY (?)";
  private static final String SELECT_FEATURES =
      "SELECT features FROM identity_candidate WHERE left_gid = ? AND right_gid = ?";
  private static final String UPSERT_CANDIDATE =
      "INSERT INTO identity_candidate (left_gid, right_gid, label, features, score, created_at, updated_at)"
          + " VALUES (?,?,?,?,?,?,?) ON CONFLICT (left_gid, right_gid) DO UPDATE SET"
          + " features = EXCLUDED.features, score = EXCLUDED.score, updated_at = EXCLUDED.updated_at";
  private static final String SELECT_CANDIDATES =
      "SELECT left_gid, right_gid, label, features, score, status, created_at, updated_at FROM identity_candidate"
          + " WHERE left_gid = ? OR right_gid = ? ORDER BY left_gid, right_gid";

  private final DataSource dataSource;

  public PostgresIdentityCandidates(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void observe(SourceKey key, UUID gid, NodeLabel label, Set<Feature> features) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        Instant now = Instant.now();
        storeFeatures(c, key, gid, label, features, now);
        Map<UUID, Set<FeatureType>> matches = findMatches(c, gid, label, features);
        for (Map.Entry<UUID, Set<FeatureType>> match : matches.entrySet()) {
          upsertCandidate(c, gid, match.getKey(), label, match.getValue(), now);
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("observe failed", e);
    }
  }

  @Override
  public List<IdentityCandidate> candidatesOf(UUID gid) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_CANDIDATES)) {
      ps.setObject(1, gid);
      ps.setObject(2, gid);
      try (ResultSet rs = ps.executeQuery()) {
        List<IdentityCandidate> result = new ArrayList<>();
        while (rs.next()) {
          result.add(
              new IdentityCandidate(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  NodeLabel.valueOf(rs.getString(3)),
                  parseTypes((String[]) rs.getArray(4).getArray()),
                  rs.getBigDecimal(5),
                  rs.getString(6),
                  rs.getTimestamp(7).toInstant(),
                  rs.getTimestamp(8).toInstant()));
        }
        return result;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("candidatesOf failed", e);
    }
  }

  private static void storeFeatures(
      Connection c, SourceKey key, UUID gid, NodeLabel label, Set<Feature> features, Instant now) throws SQLException {
    String[] names = features.stream().map(f -> f.type().name()).toArray(String[]::new);
    try (PreparedStatement ps = c.prepareStatement(DELETE_STALE)) {
      bindKey(ps, key);
      ps.setArray(4, c.createArrayOf("text", names));
      ps.executeUpdate();
    }
    try (PreparedStatement ps = c.prepareStatement(UPSERT_FEATURE)) {
      for (Feature f : features) {
        bindKey(ps, key);
        ps.setObject(4, gid);
        ps.setString(5, label.name());
        ps.setString(6, f.type().name());
        ps.setString(7, f.value());
        ps.setTimestamp(8, Timestamp.from(now));
        ps.executeUpdate();
      }
    }
  }

  /** Другие {@code gid} с общими признаками; {@code OWNER} учитывается только для пар, уже найденных по другим признакам. */
  private static Map<UUID, Set<FeatureType>> findMatches(Connection c, UUID gid, NodeLabel label, Set<Feature> features)
      throws SQLException {
    Map<UUID, Set<FeatureType>> matches = new TreeMap<>();
    for (Feature f : features) {
      if (!f.type().standalone()) {
        continue;
      }
      try (PreparedStatement ps = c.prepareStatement(FIND_OTHERS)) {
        ps.setString(1, label.name());
        ps.setString(2, f.type().name());
        ps.setString(3, f.value());
        ps.setObject(4, gid);
        ps.setInt(5, MAX_PAIRS + 1);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            matches.computeIfAbsent(rs.getObject(1, UUID.class), k -> EnumSet.noneOf(FeatureType.class)).add(f.type());
          }
        }
      }
    }
    if (matches.size() > MAX_PAIRS) {
      LOG.log(System.Logger.Level.WARNING, "identity candidates capped at {0} pairs for label {1}", MAX_PAIRS, label);
      Map<UUID, Set<FeatureType>> capped = new TreeMap<>();
      matches.entrySet().stream().limit(MAX_PAIRS).forEach(e -> capped.put(e.getKey(), e.getValue()));
      matches = capped;
    }
    for (Feature f : features) {
      if (f.type().standalone() || matches.isEmpty()) {
        continue;
      }
      Array among = c.createArrayOf("uuid", matches.keySet().toArray());
      try (PreparedStatement ps = c.prepareStatement(FIND_OTHERS_AMONG)) {
        ps.setString(1, label.name());
        ps.setString(2, f.type().name());
        ps.setString(3, f.value());
        ps.setArray(4, among);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            matches.get(rs.getObject(1, UUID.class)).add(f.type());
          }
        }
      }
    }
    return matches;
  }

  private static void upsertCandidate(
      Connection c, UUID gid, UUID other, NodeLabel label, Set<FeatureType> matched, Instant now) throws SQLException {
    UUID left = gid.compareTo(other) < 0 ? gid : other;
    UUID right = left.equals(gid) ? other : gid;
    Set<FeatureType> union = EnumSet.noneOf(FeatureType.class);
    union.addAll(matched);
    try (PreparedStatement ps = c.prepareStatement(SELECT_FEATURES)) {
      ps.setObject(1, left);
      ps.setObject(2, right);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          union.addAll(parseTypes((String[]) rs.getArray(1).getArray()));
        }
      }
    }
    String[] names = union.stream().map(Enum::name).toArray(String[]::new);
    try (PreparedStatement ps = c.prepareStatement(UPSERT_CANDIDATE)) {
      ps.setObject(1, left);
      ps.setObject(2, right);
      ps.setString(3, label.name());
      ps.setArray(4, c.createArrayOf("text", names));
      ps.setBigDecimal(5, IdentityFeatures.score(union));
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(now));
      ps.executeUpdate();
    }
  }

  private static Set<FeatureType> parseTypes(String[] names) {
    Set<FeatureType> types = EnumSet.noneOf(FeatureType.class);
    for (String name : names) {
      types.add(FeatureType.valueOf(name));
    }
    return types;
  }

  private static void bindKey(PreparedStatement ps, SourceKey key) throws SQLException {
    ps.setString(1, key.source().name());
    ps.setString(2, key.sourceType());
    ps.setString(3, key.sourceId());
  }
}
