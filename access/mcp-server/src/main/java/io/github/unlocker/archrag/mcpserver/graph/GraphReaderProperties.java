package io.github.unlocker.archrag.mcpserver.graph;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Подключение к Neo4j на read-only учётных данных. Отдельно от writer-свойств projector.
 *
 * @param uri bolt-адрес Neo4j
 * @param username пользователь только для чтения
 * @param password пароль; из env или Docker secret, в Git не хранится
 */
@ConfigurationProperties("archrag.neo4j.reader")
public record GraphReaderProperties(String uri, String username, String password) {

  public GraphReaderProperties {
    password = password == null ? "" : password;
  }

  /** Не печатает пароль в логах и исключениях. */
  @Override
  public String toString() {
    return "GraphReaderProperties[uri=" + uri + ", username=" + username + ", password=***]";
  }

  /**
   * Потолки бюджета запросов: {@code archrag.query.limits.*}.
   *
   * @param maxDepth потолок глубины обхода
   * @param maxNodes потолок числа строк
   * @param maxPaths потолок числа путей
   * @param timeout потолок таймаута транзакции
   * @param maxResponseBytes потолок размера ответа
   */
  @ConfigurationProperties("archrag.query.limits")
  public record Limits(
      int maxDepth, int maxNodes, int maxPaths, Duration timeout, DataSize maxResponseBytes) {}
}
