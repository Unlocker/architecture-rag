package io.github.unlocker.archrag.adminconsole.pg;

import io.github.unlocker.archrag.adminconsole.audit.ReadAuditContext;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Audit;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Candidate;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Checkpoint;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Conflict;
import io.github.unlocker.archrag.adminconsole.pg.Rows.DlqEntry;
import io.github.unlocker.archrag.adminconsole.pg.Rows.Event;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Чтение журнала, DLQ, identity и аудита из PostgreSQL ролью {@code archrag_console_ro}.
 *
 * <p>Инварианты: только {@code SELECT}; значения фильтров передаются именованными параметрами, в текст запроса
 * попадают только константные фрагменты {@code WHERE}; колонок payload в выборках нет. Текстовые поля из
 * источников обрезаются до {@value #MAX_TEXT} символов.
 */
@Repository
public class ConsoleReadRepository {

  static final int MAX_TEXT = 500;
  /** Предел размера {@code result} в ответе аудита: больший jsonb не отдаётся, выставляется {@code resultTruncated}. */
  static final int MAX_RESULT_CHARS = 4000;

  private static final String EVENT_COLUMNS =
      "source, event_id, type, subject, source_type, source_id, source_version, correlation_id, sync_run_id,"
          + " schema_version, status, attempts, error_code, payload_key, payload_hash, received_at, updated_at";

  private static final JsonMapper JSON = new JsonMapper();

  private final JdbcClient jdbc;

  ConsoleReadRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- источники ---

  /** Checkpoints всех consumer-ов по {@code inbox_event.source}-значениям. */
  public Map<String, List<Checkpoint>> checkpoints() {
    Map<String, List<Checkpoint>> out = new LinkedHashMap<>();
    jdbc.sql("SELECT consumer, source, cursor, updated_at FROM consumer_checkpoint ORDER BY consumer, source")
        .query(
            (rs, i) -> {
              out.computeIfAbsent(SyncSources.toCode(rs.getString("source")), k -> new ArrayList<>())
                  .add(new Checkpoint(rs.getString("consumer"), rs.getString("cursor"), instant(rs, "updated_at")));
              return 0;
            })
        .list();
    audit("consumer_checkpoint", out.values().stream().mapToInt(List::size).sum());
    return out;
  }

  /** Число событий по (источнику, статусу). */
  public Map<String, Map<String, Long>> eventCounts() {
    Map<String, Map<String, Long>> out = new LinkedHashMap<>();
    jdbc.sql("SELECT source, status, count(*) AS n FROM inbox_event GROUP BY source, status")
        .query(
            (rs, i) -> {
              out.computeIfAbsent(SyncSources.toCode(rs.getString("source")), k -> new LinkedHashMap<>())
                  .put(rs.getString("status"), rs.getLong("n"));
              return 0;
            })
        .list();
    audit("inbox_event_counts", out.values().stream().mapToInt(Map::size).sum());
    return out;
  }

  /** Время последнего {@code PROJECTED} по источнику ({@code updated_at} события). */
  public Map<String, Instant> lastProjected() {
    Map<String, Instant> out = new LinkedHashMap<>();
    jdbc.sql("SELECT source, max(updated_at) AS at FROM inbox_event WHERE status = 'PROJECTED' GROUP BY source")
        .query(
            (rs, i) -> {
              out.put(SyncSources.toCode(rs.getString("source")), instant(rs, "at"));
              return 0;
            })
        .list();
    audit("inbox_event_last_projected", out.size());
    return out;
  }

  // --- события ---

  /** Страница {@code inbox_event}: новые сверху; {@code from} включительно, {@code to} исключительно. */
  public Page<Event> events(String source, String status, Instant from, Instant to, PageRequest page) {
    var where = new Where();
    where.eq("source = :source", "source", source);
    where.eq("status = :status", "status", status);
    where.eq("received_at >= :from", "from", from == null ? null : Timestamp.from(from));
    where.eq("received_at < :to", "to", to == null ? null : Timestamp.from(to));
    return page(
        "inbox_event",
        EVENT_COLUMNS,
        where,
        "received_at DESC, source, event_id",
        page,
        (rs, i) -> event(rs));
  }

  /** События с данным {@code event_id}: ключ составной, поэтому источник может быть не задан. */
  public List<Event> eventsById(String eventId, String source) {
    var where = new Where();
    where.eq("event_id = :eventId", "eventId", eventId);
    where.eq("source = :source", "source", source);
    List<Event> events =
        where.apply(jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM inbox_event" + where.clause() + " ORDER BY source LIMIT 2"))
            .query((rs, i) -> event(rs))
            .list();
    audit("inbox_event_by_id", events.size());
    return events;
  }

  /** {@code error_reason} события. */
  public Optional<String> errorReason(String source, String eventId) {
    Optional<String> reason = jdbc.sql("SELECT error_reason FROM inbox_event WHERE source = :source AND event_id = :eventId")
        .param("source", source)
        .param("eventId", eventId)
        .query((rs, i) -> truncate(rs.getString("error_reason")))
        .optional();
    audit("inbox_event_error_reason", reason.isPresent() ? 1 : 0);
    return reason;
  }

  /** Записи DLQ события, старые первыми. */
  public List<DlqEntry> dlqOfEvent(String source, String eventId) {
    List<DlqEntry> entries = jdbc.sql(
            "SELECT id, source, event_id, error_code, reason, payload_key, created_at, replayed_at FROM dlq_entry"
                + " WHERE source = :source AND event_id = :eventId ORDER BY created_at, id")
        .param("source", source)
        .param("eventId", eventId)
        .query((rs, i) -> dlq(rs))
        .list();
    audit("dlq_entry_of_event", entries.size());
    return entries;
  }

  // --- DLQ ---

  /** Страница {@code dlq_entry}; {@code replayed}: {@code true} — обработанные, {@code false} — открытые. */
  public Page<DlqEntry> dlq(String source, Boolean replayed, PageRequest page) {
    var where = new Where();
    where.eq("source = :source", "source", source);
    if (replayed != null) {
      where.fixed(replayed ? "replayed_at IS NOT NULL" : "replayed_at IS NULL");
    }
    return page(
        "dlq_entry",
        "id, source, event_id, error_code, reason, payload_key, created_at, replayed_at",
        where,
        "created_at DESC, id DESC",
        page,
        (rs, i) -> dlq(rs));
  }

  // --- identity ---

  /** Страница {@code source_conflict}. */
  public Page<Conflict> conflicts(String status, String gid, PageRequest page) {
    var where = new Where();
    where.eq("status = :status", "status", status);
    where.eq("gid = CAST(:gid AS uuid)", "gid", gid);
    return page(
        "source_conflict",
        "gid, property, dissent_source, dissent_type, dissent_id, dissent_value, master_source, master_type,"
            + " master_id, master_value, status, opened_at, updated_at, resolved_at",
        where,
        "updated_at DESC, gid, property, dissent_source, dissent_type, dissent_id",
        page,
        (rs, i) ->
            new Conflict(
                rs.getString("gid"),
                rs.getString("property"),
                rs.getString("dissent_source"),
                rs.getString("dissent_type"),
                rs.getString("dissent_id"),
                truncate(rs.getString("dissent_value")),
                rs.getString("master_source"),
                rs.getString("master_type"),
                rs.getString("master_id"),
                truncate(rs.getString("master_value")),
                rs.getString("status"),
                instant(rs, "opened_at"),
                instant(rs, "updated_at"),
                instant(rs, "resolved_at")));
  }

  /** Страница {@code identity_candidate}. */
  public Page<Candidate> candidates(String status, String labelFamily, PageRequest page) {
    var where = new Where();
    where.eq("status = :status", "status", status);
    where.eq("label_family = :labelFamily", "labelFamily", labelFamily);
    return page(
        "identity_candidate",
        "left_source, left_type, left_id, right_source, right_type, right_id, label_family, matched::text AS matched,"
            + " score, status, first_seen_at, updated_at",
        where,
        "score DESC, updated_at DESC, left_source, left_type, left_id, right_source, right_type, right_id",
        page,
        (rs, i) ->
            new Candidate(
                rs.getString("left_source"),
                rs.getString("left_type"),
                rs.getString("left_id"),
                rs.getString("right_source"),
                rs.getString("right_type"),
                rs.getString("right_id"),
                rs.getString("label_family"),
                JSON.readTree(rs.getString("matched")),
                rs.getDouble("score"),
                rs.getString("status"),
                instant(rs, "first_seen_at"),
                instant(rs, "updated_at")));
  }

  // --- аудит ---

  /** Страница {@code admin_audit}. */
  public Page<Audit> audit(String operation, String status, Instant from, Instant to, PageRequest page) {
    var where = new Where();
    where.eq("operation = :operation", "operation", operation);
    where.eq("status = :status", "status", status);
    where.eq("started_at >= :from", "from", from == null ? null : Timestamp.from(from));
    where.eq("started_at < :to", "to", to == null ? null : Timestamp.from(to));
    return page(
        "admin_audit",
        "id, operation, actor, status, replay_id, request::text AS request,"
            + " CASE WHEN length(result::text) > " + MAX_RESULT_CHARS + " THEN NULL ELSE result::text END AS result,"
            + " length(result::text) > " + MAX_RESULT_CHARS + " AS result_truncated, error, started_at, finished_at",
        where,
        "started_at DESC, id DESC",
        page,
        (rs, i) -> {
          String result = rs.getString("result");
          String request = rs.getString("request");
          return new Audit(
              rs.getLong("id"),
              rs.getString("operation"),
              rs.getString("actor"),
              rs.getString("status"),
              rs.getString("replay_id"),
              request == null ? null : JSON.readTree(request),
              result == null ? null : JSON.readTree(result),
              rs.getBoolean("result_truncated"),
              truncate(rs.getString("error")),
              instant(rs, "started_at"),
              instant(rs, "finished_at"));
        });
  }

  // --- внутреннее ---

  /** Таблица, колонки и сортировка — константы вызывающего кода; значения фильтров — параметры. */
  private <T> Page<T> page(
      String table, String columns, Where where, String orderBy, PageRequest page, RowMapper<T> mapper) {
    long total =
        where
            .apply(jdbc.sql("SELECT count(*) FROM " + table + where.clause()))
            .query(Long.class)
            .single();
    List<T> items =
        where
            .apply(
                jdbc.sql(
                    "SELECT " + columns + " FROM " + table + where.clause() + " ORDER BY " + orderBy
                        + " LIMIT :limit OFFSET :offset"))
            .param("limit", page.size())
            .param("offset", page.offset())
            .query(mapper)
            .list();
    audit(table, items.size());
    return new Page<>(items, page.page(), page.size(), total);
  }

  /** Сообщает аудиту чтения текущего запроса, какая выборка выполнена и сколько строк вернула. */
  private static void audit(String queryId, long rows) {
    ReadAuditContext context = ReadAuditContext.current();
    if (context != null) {
      context.recordSql(queryId, rows);
    }
  }

  private static Event event(ResultSet rs) throws SQLException {
    return new Event(
        SyncSources.toCode(rs.getString("source")),
        rs.getString("event_id"),
        rs.getString("type"),
        rs.getString("subject"),
        rs.getString("source_type"),
        rs.getString("source_id"),
        rs.getString("source_version"),
        rs.getString("correlation_id"),
        rs.getString("sync_run_id"),
        rs.getString("schema_version"),
        rs.getString("status"),
        rs.getInt("attempts"),
        rs.getString("error_code"),
        rs.getString("payload_key"),
        rs.getString("payload_hash"),
        instant(rs, "received_at"),
        instant(rs, "updated_at"));
  }

  private static DlqEntry dlq(ResultSet rs) throws SQLException {
    return new DlqEntry(
        rs.getLong("id"),
        SyncSources.toCode(rs.getString("source")),
        rs.getString("event_id"),
        rs.getString("error_code"),
        truncate(rs.getString("reason")),
        rs.getString("payload_key"),
        instant(rs, "created_at"),
        instant(rs, "replayed_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static String truncate(String text) {
    return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
  }

  /** Условия {@code WHERE} из константных фрагментов с именованными параметрами; {@code null}-значения пропускаются. */
  private static final class Where {
    private final List<String> fragments = new ArrayList<>();
    private final Map<String, Object> params = new LinkedHashMap<>();

    void eq(String fragment, String name, Object value) {
      if (value != null) {
        fragments.add(fragment);
        params.put(name, value);
      }
    }

    void fixed(String fragment) {
      fragments.add(fragment);
    }

    String clause() {
      return fragments.isEmpty() ? "" : " WHERE " + String.join(" AND ", fragments);
    }

    JdbcClient.StatementSpec apply(JdbcClient.StatementSpec spec) {
      return spec.params(params);
    }
  }
}
