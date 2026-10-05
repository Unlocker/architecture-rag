package io.github.unlocker.archrag.identityresolution;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * {@link IdentityCandidates} на PostgreSQL (JDBC без ORM); схема из {@code V4__identity_candidate.sql}.
 *
 * <p>Каждая операция — одна транзакция. Параллельные {@code record} с одним значением признака
 * сериализуются advisory-lock'ом на {@code (семейство, вид, значение)}: иначе при READ COMMITTED обе
 * транзакции не увидели бы друг друга и пара была бы потеряна. Общий {@code gid} проверяется по
 * {@code identity_mapping} той же БД. Если у одного признака больше {@value #MAX_MATCHES} совпадений,
 * кандидаты по нему не создаются (WARN с количеством, без значения); если у пары такой признак пропущен,
 * {@code matched} при upsert перезаписывается без него (допущение PoC).
 */
public final class PostgresIdentityCandidates implements IdentityCandidates {

  /** Предел совпадений одного признака, сверх которого кандидаты по нему не создаются. */
  public static final int MAX_MATCHES = 50;

  private static final System.Logger LOG = System.getLogger(PostgresIdentityCandidates.class.getName());

  private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
  private static final String DELETE_FEATURES =
      "DELETE FROM identity_feature WHERE source = ? AND source_type = ? AND source_id = ?";
  private static final String INSERT_FEATURE =
      "INSERT INTO identity_feature (source, source_type, source_id, feature, value, label_family, updated_at)"
          + " VALUES (?,?,?,?,?,?,?)";
  private static final String FIND_OTHERS =
      "SELECT f.source, f.source_type, f.source_id FROM identity_feature f"
          + " WHERE f.feature = ? AND f.label_family = ? AND f.value = ?"
          + " AND NOT (f.source = ? AND f.source_type = ? AND f.source_id = ?)"
          + " AND NOT EXISTS (SELECT 1 FROM identity_mapping a JOIN identity_mapping b ON a.gid = b.gid"
          + " WHERE a.source = ? AND a.source_type = ? AND a.source_id = ?"
          + " AND b.source = f.source AND b.source_type = f.source_type AND b.source_id = f.source_id)"
          + " ORDER BY f.source, f.source_type, f.source_id LIMIT ?";
  private static final String HAS_FEATURE =
      "SELECT 1 FROM identity_feature WHERE source = ? AND source_type = ? AND source_id = ?"
          + " AND feature = ? AND value = ?";
  private static final String UPSERT_CANDIDATE =
      "INSERT INTO identity_candidate (left_source, left_type, left_id, right_source, right_type, right_id,"
          + " label_family, matched, score, first_seen_at, updated_at) VALUES (?,?,?,?,?,?,?,"
          + " (SELECT jsonb_agg(jsonb_build_object('feature', t.f, 'value', t.v) ORDER BY t.o)"
          + " FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS t(f, v, o)), ?, ?, ?)"
          + " ON CONFLICT (left_source, left_type, left_id, right_source, right_type, right_id) DO UPDATE SET"
          + " matched = EXCLUDED.matched, score = EXCLUDED.score, updated_at = EXCLUDED.updated_at"
          + " RETURNING status, first_seen_at, updated_at";
  private static final String SELECT_CANDIDATES =
      "SELECT left_source, left_type, left_id, right_source, right_type, right_id, label_family,"
          + " ARRAY(SELECT e->>'feature' FROM jsonb_array_elements(matched) WITH ORDINALITY t(e, o) ORDER BY o),"
          + " ARRAY(SELECT e->>'value' FROM jsonb_array_elements(matched) WITH ORDINALITY t(e, o) ORDER BY o),"
          + " score, status, first_seen_at, updated_at FROM identity_candidate"
          + " WHERE (left_source = ? AND left_type = ? AND left_id = ?)"
          + " OR (right_source = ? AND right_type = ? AND right_id = ?)"
          + " ORDER BY left_source, left_type, left_id, right_source, right_type, right_id";

  private final DataSource dataSource;

  public PostgresIdentityCandidates(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public List<IdentityCandidate> record(SourceKey key, NodeLabel label, Set<Feature> features) {
    NodeLabel family = LabelFamily.of(label);
    List<Feature> own = features.stream().sorted(Comparator.comparing(Feature::kind).thenComparing(Feature::value)).toList();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        lock(c, "key|" + key);
        lockValues(c, family, own);
        replaceFeatures(c, key, family, own);
        Map<SourceKey, List<Feature>> matches = findMatches(c, key, family, own);
        List<IdentityCandidate> result = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<SourceKey, List<Feature>> match : matches.entrySet()) {
          result.add(upsertCandidate(c, key, match.getKey(), family, match.getValue(), now));
        }
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("record failed", e);
    }
  }

  @Override
  public void forget(SourceKey key) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement(DELETE_FEATURES)) {
        lock(c, "key|" + key);
        bindKey(ps, 1, key);
        ps.executeUpdate();
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("forget failed", e);
    }
  }

  @Override
  public List<IdentityCandidate> candidatesOf(SourceKey key) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_CANDIDATES)) {
      bindKey(ps, bindKey(ps, 1, key), key);
      try (ResultSet rs = ps.executeQuery()) {
        List<IdentityCandidate> result = new ArrayList<>();
        while (rs.next()) {
          String[] kinds = (String[]) rs.getArray(8).getArray();
          String[] values = (String[]) rs.getArray(9).getArray();
          List<Feature> matched = new ArrayList<>();
          for (int i = 0; i < kinds.length; i++) {
            matched.add(new Feature(FeatureKind.valueOf(kinds[i]), values[i]));
          }
          result.add(
              new IdentityCandidate(
                  readKey(rs, 1),
                  readKey(rs, 4),
                  NodeLabel.valueOf(rs.getString(7)),
                  matched,
                  rs.getBigDecimal(10),
                  rs.getString(11),
                  rs.getTimestamp(12).toInstant(),
                  rs.getTimestamp(13).toInstant()));
        }
        return result;
      }
    } catch (SQLException e) {
      throw new IdentityStoreException("candidatesOf failed", e);
    }
  }

  /** Сначала блокируется сам ключ (параллельные {@code record} одной записи иначе упираются в PK), затем значения. Порядок детерминирован (features уже отсортированы): взаимной блокировки нет. */
  private static void lockValues(Connection c, NodeLabel family, List<Feature> features) throws SQLException {
    for (Feature f : features) {
      if (!f.kind().strong()) {
        continue;
      }
      lock(c, family.name() + "|" + f.kind().name() + "|" + f.value());
    }
  }

  private static void lock(Connection c, String name) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(LOCK)) {
      ps.setString(1, name);
      ps.execute();
    }
  }

  private static void replaceFeatures(Connection c, SourceKey key, NodeLabel family, List<Feature> features)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(DELETE_FEATURES)) {
      bindKey(ps, 1, key);
      ps.executeUpdate();
    }
    Timestamp now = Timestamp.from(Instant.now());
    try (PreparedStatement ps = c.prepareStatement(INSERT_FEATURE)) {
      for (Feature f : features) {
        int i = bindKey(ps, 1, key);
        ps.setString(i++, f.kind().name());
        ps.setString(i++, f.value());
        ps.setString(i++, family.name());
        ps.setTimestamp(i, now);
        ps.executeUpdate();
      }
    }
  }

  /** Другие ключи с общими сильными признаками; {@code OWNER} повышает score, но пару сам не создаёт. */
  private static Map<SourceKey, List<Feature>> findMatches(
      Connection c, SourceKey key, NodeLabel family, List<Feature> own) throws SQLException {
    Map<SourceKey, List<Feature>> matches = new LinkedHashMap<>();
    for (Feature f : own) {
      if (!f.kind().strong()) {
        continue;
      }
      List<SourceKey> others = new ArrayList<>();
      try (PreparedStatement ps = c.prepareStatement(FIND_OTHERS)) {
        ps.setString(1, f.kind().name());
        ps.setString(2, family.name());
        ps.setString(3, f.value());
        int i = bindKey(ps, 4, key);
        i = bindKey(ps, i, key);
        ps.setInt(i, MAX_MATCHES + 1);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            others.add(readKey(rs, 1));
          }
        }
      }
      if (others.size() > MAX_MATCHES) {
        LOG.log(System.Logger.Level.WARNING,
            "identity candidates skipped: feature {0} of {1} matches more than {2} records", f.kind(), family, MAX_MATCHES);
        continue;
      }
      for (SourceKey other : others) {
        matches.computeIfAbsent(other, k -> new ArrayList<>()).add(f);
      }
    }
    for (Feature f : own) {
      if (f.kind().strong()) {
        continue;
      }
      for (Map.Entry<SourceKey, List<Feature>> match : matches.entrySet()) {
        try (PreparedStatement ps = c.prepareStatement(HAS_FEATURE)) {
          int i = bindKey(ps, 1, match.getKey());
          ps.setString(i++, f.kind().name());
          ps.setString(i, f.value());
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              match.getValue().add(f);
            }
          }
        }
      }
    }
    return matches;
  }

  private static IdentityCandidate upsertCandidate(
      Connection c, SourceKey self, SourceKey other, NodeLabel family, List<Feature> matched, Instant now)
      throws SQLException {
    boolean swap = SourceKeys.compare(self, other) > 0;
    SourceKey left = swap ? other : self;
    SourceKey right = swap ? self : other;
    String[] kinds = matched.stream().map(f -> f.kind().name()).toArray(String[]::new);
    String[] values = matched.stream().map(Feature::value).toArray(String[]::new);
    var score = FeatureKind.score(matched.stream().map(Feature::kind).toList());
    try (PreparedStatement ps = c.prepareStatement(UPSERT_CANDIDATE)) {
      int i = bindKey(ps, 1, left);
      i = bindKey(ps, i, right);
      ps.setString(i++, family.name());
      ps.setArray(i++, c.createArrayOf("text", kinds));
      ps.setArray(i++, c.createArrayOf("text", values));
      ps.setBigDecimal(i++, score);
      ps.setTimestamp(i++, Timestamp.from(now));
      ps.setTimestamp(i, Timestamp.from(now));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new IdentityCandidate(
            left, right, family, matched, score, rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant());
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
