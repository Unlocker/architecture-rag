package io.github.unlocker.archrag.ingestionservice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Аудит админских операций в таблице {@code admin_audit}. Запись {@code STARTED} создаётся до операции и потом
 * обновляется; ошибка записи пробрасывается, и операция не выполняется. В {@code request} и {@code result} попадают
 * только параметры и счётчики, но не payload источников.
 */
@Component
public class AdminAudit {

  private static final int MAX_ERROR_LENGTH = 200;

  private final DataSource dataSource;
  private final JsonMapper json;

  public AdminAudit(DataSource dataSource, JsonMapper json) {
    this.dataSource = dataSource;
    this.json = json;
  }

  /** Фиксирует начало операции и возвращает id записи. */
  public long start(String operation, String actor, Map<String, ?> request, String replayId) {
    String sql = "INSERT INTO admin_audit (operation, actor, request, replay_id, status, started_at)"
        + " VALUES (?, ?, ?::jsonb, ?, 'STARTED', ?) RETURNING id";
    try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, operation);
      ps.setString(2, actor);
      ps.setString(3, json.writeValueAsString(request));
      ps.setString(4, replayId);
      ps.setTimestamp(5, Timestamp.from(Instant.now()));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("audit write failed", e);
    }
  }

  /** Фиксирует итог: {@code SUCCEEDED} со счётчиками либо {@code FAILED} с классом ошибки. */
  public void finish(long id, boolean succeeded, Map<String, ?> result, String error) {
    String sql = "UPDATE admin_audit SET status = ?, result = ?::jsonb, error = ?, finished_at = ? WHERE id = ?";
    try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, succeeded ? "SUCCEEDED" : "FAILED");
      ps.setString(2, result == null ? null : json.writeValueAsString(result));
      ps.setString(3, error == null ? null : truncate(error));
      ps.setTimestamp(4, Timestamp.from(Instant.now()));
      ps.setLong(5, id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("audit write failed", e);
    }
  }

  private static String truncate(String s) {
    return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
  }
}
