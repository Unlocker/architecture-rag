package io.github.unlocker.archrag.ingestionservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Подключение к Neo4j с учётными данными writer; значения приходят из env/Docker secrets. */
@ConfigurationProperties("archrag.neo4j")
public record Neo4jProperties(String uri, String username, String password) {

  @Override
  public String toString() {
    return "Neo4jProperties[uri=" + uri + "]";
  }
}
