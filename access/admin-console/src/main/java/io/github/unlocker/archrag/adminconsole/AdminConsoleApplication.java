package io.github.unlocker.archrag.adminconsole;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Точка входа админ-консоли: статика SPA и read-only API графа; только чтение, без writer-учётки Neo4j. */
@SpringBootApplication
public class AdminConsoleApplication {

  public static void main(String[] args) {
    SpringApplication.run(AdminConsoleApplication.class, args);
  }
}
