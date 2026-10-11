package io.github.unlocker.archrag.integrationtests;

import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStore;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
import java.net.URI;
import java.time.Clock;

/**
 * Заглушка {@code eam-api} с seed {@link EamApiSeed} и адаптер EAM, читающий её вместе со старой заглушкой
 * (TEAM, SERVICE_DEPENDENCY): так в ITs собирается источник EAM после перехода на ключ {@code id}.
 */
final class EamApiSupport implements AutoCloseable {

  static final String TOKEN = "it-eam-api-token";

  final EamApiStore store;
  private final EamApiStub stub;

  EamApiSupport(Clock clock) {
    this.store = EamApiSeed.seeded(clock);
    this.stub = new EamApiStub(store, TOKEN);
  }

  /** Адаптер EAM: {@code eam-api} + старая заглушка по {@code legacyUri}. */
  SourceAdapter adapter(URI legacyUri, String webhookSecret, EventJournal journal, RawPayloadStore rawStore) {
    return EamAdapter.create(EamAdapter.config(legacyUri, webhookSecret), new EamAdapter.EamApi(stub.baseUri(), TOKEN),
        journal, rawStore);
  }

  @Override
  public void close() {
    stub.close();
  }
}
