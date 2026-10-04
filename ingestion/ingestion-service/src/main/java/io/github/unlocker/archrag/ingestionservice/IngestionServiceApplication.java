package io.github.unlocker.archrag.ingestionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Ingestion-сервис: админский эндпоинт replay/rebuild/reconcile/crosswalk (E1.7). Во внешний ingress не публикуется. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestionServiceApplication.class, args);
  }
}
