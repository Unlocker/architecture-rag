package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.ReconciliationTrigger;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.net.URI;
import javax.sql.DataSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Явная сборка компонентов ingestion: журнал, raw storage, identity, projector. Миграции PostgreSQL применяются
 * только через {@link JournalMigrations#apply} (Flyway из Boot не подключён), схема Neo4j — {@link Neo4jSchema#apply}.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionServiceConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(IngestionServiceConfiguration.class);

  /** Применяет миграции до создания бинов, которым нужна схема. */
  @Bean
  PostgresEventJournal eventJournal(DataSource dataSource) {
    JournalMigrations.apply(dataSource);
    return new PostgresEventJournal(dataSource);
  }

  @Bean
  IdentityMapping identityMapping(DataSource dataSource, PostgresEventJournal migrated) {
    // Зависимость от журнала гарантирует, что миграции уже применены.
    return new PostgresIdentityMapping(dataSource);
  }

  @Bean(destroyMethod = "close")
  Driver neo4jDriver(Neo4jProperties props) {
    Driver driver = GraphDatabase.driver(props.uri(), AuthTokens.basic(props.username(), props.password()));
    Neo4jSchema.apply(driver);
    return driver;
  }

  @Bean(destroyMethod = "close")
  S3RawPayloadStore rawPayloadStore(S3Properties props) {
    S3RawPayloadStore store =
        S3RawPayloadStore.create(URI.create(props.endpoint()), props.accessKey(), props.secretKey(), props.bucket());
    store.ensureBucket();
    return store;
  }

  @Bean
  GraphProjector graphProjector(Driver driver) {
    return new GraphProjector(driver, AuthorityMatrix.defaults());
  }

  /** До влива reconciliation (UNLOCKER-169) маркер {@code snapshot-complete} никуда не передаётся. */
  @Bean
  ReconciliationTrigger reconciliationTrigger() {
    return (source, syncRunId, eventId) ->
        LOG.warn("snapshot-complete is not forwarded: reconciliation is not wired (source={}, syncRunId={})", source, syncRunId);
  }

  @Bean
  EventProcessor eventProcessor(
      EventJournal journal,
      GraphProjector projector,
      IdentityMapping identity,
      ReconciliationTrigger reconciliation) {
    return new EventProcessor(journal, Normalizer.standard(projector::isActive), identity, projector, reconciliation);
  }
}
