package io.github.unlocker.archrag.adminconsole.pg;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Единственный пул PostgreSQL консоли (read-only роль). Автоконфигурация {@code spring.datasource.*} и Flyway не
 * используются: схему ведёт ingestion-сервис, консоль её не мигрирует. Пул ленивый: соединение открывается при
 * первом запросе, соединения только на чтение.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsolePgProperties.class)
public class ConsolePgConfiguration {

  @Bean(destroyMethod = "close")
  DataSource consolePgDataSource(ConsolePgProperties properties) {
    var ds = new HikariDataSource();
    ds.setPoolName("console-pg");
    ds.setJdbcUrl(properties.url());
    ds.setUsername(properties.username());
    ds.setPassword(properties.password());
    ds.setReadOnly(true);
    ds.setMaximumPoolSize(4);
    ds.setMinimumIdle(0);
    // -1: пул не пытается соединиться при создании, сбой PostgreSQL проявится на запросе, а не при старте.
    ds.setInitializationFailTimeout(-1);
    return ds;
  }

  @Bean
  JdbcClient consoleJdbcClient(DataSource consolePgDataSource) {
    return JdbcClient.create(consolePgDataSource);
  }
}
