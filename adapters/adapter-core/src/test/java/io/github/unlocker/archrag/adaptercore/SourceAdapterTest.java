package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceAdapterTest {

  private static final AdapterConfig CONFIG =
      AdapterConfig.of(SourceSystem.EAM, URI.create("http://unused.invalid"), "secret");

  /** Коннектор, который отдаёт один объект одним snapshot и помнит, что его закрыли. */
  private static final class OneObject implements SourceConnector, AutoCloseable {
    final AtomicInteger fetches = new AtomicInteger();
    final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public SourceSystem system() {
      return SourceSystem.EAM;
    }

    @Override
    public ChangePage fetchChanges(String cursor, int limit) {
      fetches.incrementAndGet();
      var change = new SourceChange("PLATFORM", "3101", 1, ChangeOperation.UPSERT, Completeness.COMPLETE,
          Instant.parse("2026-10-01T09:00:00Z"), Map.of("id", 3101L));
      return new ChangePage(cursor == null ? List.of(change) : List.of(), "next", false, cursor == null);
    }

    @Override
    public Optional<SourceChange> fetchById(String sourceType, String sourceId) {
      return Optional.empty();
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  @Test
  void adapterReadsThroughTheGivenConnectorAndClosesIt() {
    var connector = new OneObject();
    var journal = new InMemoryJournal();
    var adapter = new SourceAdapter(CONFIG, connector, journal, new InMemoryRawStore());

    var result = adapter.poller().pollOnce();

    assertThat(adapter.connector()).isSameAs(connector);
    assertThat(result.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(connector.fetches.get()).isEqualTo(1);
    assertThat(journal.events.values()).anyMatch(e -> e.id().contains("PLATFORM/3101"));

    adapter.close();
    assertThat(connector.closed).isTrue();
  }

  @Test
  void defaultConstructorStillBuildsHttpConnectorForTheConfiguredSource() {
    try (var adapter = new SourceAdapter(CONFIG, new InMemoryJournal(), new InMemoryRawStore())) {
      assertThat(adapter.connector()).isInstanceOf(HttpSourceConnector.class);
      assertThat(adapter.connector().system()).isEqualTo(SourceSystem.EAM);
    }
  }
}
