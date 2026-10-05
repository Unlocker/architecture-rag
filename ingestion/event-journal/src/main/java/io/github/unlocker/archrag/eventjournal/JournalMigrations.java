package io.github.unlocker.archrag.eventjournal;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Применяет Flyway-миграции схемы PostgreSQL из {@code db/migration}; повторный вызов безопасен. */
public final class JournalMigrations {

  private JournalMigrations() {}

  /** Выполняет {@code migrate} на {@code dataSource}. */
  public static void apply(DataSource dataSource) {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }
}
