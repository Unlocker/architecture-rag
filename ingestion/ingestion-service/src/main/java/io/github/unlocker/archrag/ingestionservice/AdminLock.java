package io.github.unlocker.archrag.ingestionservice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL advisory lock на все админские операции: одновременно выполняется одна. Замок сессионный, поэтому
 * соединение удерживается до {@link Lease#close()}.
 */
@Component
public class AdminLock {

  /** Произвольная константа, общая для всех экземпляров сервиса. */
  static final long KEY = 0x41524348_41444D31L;

  private final DataSource dataSource;

  public AdminLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Берёт замок без ожидания.
   *
   * @throws AdminBusyException если замок занят
   */
  public Lease acquire() {
    Connection c = null;
    try {
      c = dataSource.getConnection();
      try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        ps.setLong(1, KEY);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (!rs.getBoolean(1)) {
            c.close();
            throw new AdminBusyException();
          }
        }
      }
      return new Lease(c);
    } catch (SQLException e) {
      closeQuietly(c);
      throw new IllegalStateException("admin lock failed", e);
    }
  }

  private static void closeQuietly(Connection c) {
    if (c != null) {
      try {
        c.close();
      } catch (SQLException ignored) {
        // Исходная ошибка важнее; соединение всё равно возвращается в пул как сломанное.
      }
    }
  }

  /** Удерживаемый замок. */
  public static final class Lease implements AutoCloseable {

    private final Connection connection;

    private Lease(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void close() {
      try (connection; PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        ps.setLong(1, KEY);
        ps.execute();
      } catch (SQLException e) {
        throw new IllegalStateException("admin unlock failed", e);
      }
    }
  }
}
