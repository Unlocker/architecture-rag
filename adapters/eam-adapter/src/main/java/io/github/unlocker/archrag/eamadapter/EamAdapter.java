package io.github.unlocker.archrag.eamadapter;

import io.github.unlocker.archrag.adaptercore.AdapterConfig;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.adaptercore.HttpSourceConnector;
import java.net.URI;

/**
 * Адаптер источника {@link SourceSystem#EAM}: приём webhook, polling и snapshot делает {@code
 * adapter-core}, здесь только привязка к источнику. Журнал и хранилище raw приходят через
 * интерфейсы {@code event-schemas}; Neo4j-доступа у адаптера нет.
 */
public final class EamAdapter {

  /** Источник, который обслуживает адаптер. */
  public static final SourceSystem SYSTEM = SourceSystem.EAM;

  private EamAdapter() {}

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

  /**
   * Адрес и токен {@code eam-api} ({@code docs/eam-api.yaml}). Токен в {@link #toString()} не показывается.
   *
   * @param baseUri базовый URL API, например {@code http://eam-api-stub:8080}
   * @param token токен для {@code Authorization: Token ...}, из env/secret
   */
  public record EamApi(URI baseUri, String token) {

    @Override
    public String toString() {
      return "EamApi[baseUri=" + baseUri + ", token=***]";
    }
  }

  /**
   * Адаптер, читающий {@code eam-api}: {@code PLATFORM}/{@code SOLUTION}/{@code IT_SYSTEM} с ключом {@code id}.
   * {@code TEAM} и {@code SERVICE_DEPENDENCY} по-прежнему приходят из старой заглушки по {@code config.sourceBaseUri()}
   * (см. {@link CompositeEamConnector}).
   *
   * @throws IllegalArgumentException если {@code config} относится к другому источнику
   */
  public static SourceAdapter create(
      AdapterConfig config, EamApi api, EventJournal journal, RawPayloadStore rawStore) {
    if (config.system() != SYSTEM) {
      throw new IllegalArgumentException("config is for " + config.system() + ", expected " + SYSTEM);
    }
    var connector = new CompositeEamConnector(
        new EamApiConnector(api.baseUri(), api.token(), config.pageSize()),
        new HttpSourceConnector(SYSTEM, config.sourceBaseUri()));
    return new SourceAdapter(config, connector, journal, rawStore);
  }
}
