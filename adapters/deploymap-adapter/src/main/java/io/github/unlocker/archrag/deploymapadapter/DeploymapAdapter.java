package io.github.unlocker.archrag.deploymapadapter;

import io.github.unlocker.archrag.adaptercore.AdapterConfig;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.net.URI;

/**
 * Адаптер источника {@link SourceSystem#DEPLOY_MAP}: приём webhook, polling и snapshot делает {@code
 * adapter-core}, здесь только привязка к источнику. Журнал и хранилище raw приходят через
 * интерфейсы {@code event-schemas}; Neo4j-доступа у адаптера нет.
 */
public final class DeploymapAdapter {

  /** Источник, который обслуживает адаптер. */
  public static final SourceSystem SYSTEM = SourceSystem.DEPLOY_MAP;

  private DeploymapAdapter() {}

  /** Конфигурация по умолчанию для API источника по адресу {@code sourceBaseUri}. */
  public static AdapterConfig config(URI sourceBaseUri, String webhookSecret) {
    return AdapterConfig.of(SYSTEM, sourceBaseUri, webhookSecret);
  }

  /**
   * Собирает адаптер.
   *
   * @throws IllegalArgumentException если {@code config} относится к другому источнику
   */
  public static SourceAdapter create(
      AdapterConfig config, EventJournal journal, RawPayloadStore rawStore) {
    if (config.system() != SYSTEM) {
      throw new IllegalArgumentException("config is for " + config.system() + ", expected " + SYSTEM);
    }
    return new SourceAdapter(config, journal, rawStore);
  }
}
