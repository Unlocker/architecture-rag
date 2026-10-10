package io.github.unlocker.archrag.adminconsole.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Подключение консоли к PostgreSQL ролью {@code archrag_console_ro} (только {@code SELECT}).
 *
 * @param url JDBC URL ({@code ARCHRAG_CONSOLE_PG_URL})
 * @param username роль только для чтения ({@code ARCHRAG_CONSOLE_PG_USERNAME})
 * @param password пароль; из env или Docker secret, в Git не хранится ({@code ARCHRAG_CONSOLE_PG_PASSWORD})
 */
@ConfigurationProperties("archrag.console-pg")
public record ConsolePgProperties(String url, String username, String password) {

  /** Не печатает пароль в логах и исключениях. */
  @Override
  public String toString() {
    return "ConsolePgProperties[url=" + url + ", username=" + username + ", password=***]";
  }
}
