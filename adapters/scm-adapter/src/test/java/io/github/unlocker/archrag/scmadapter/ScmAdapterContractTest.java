package io.github.unlocker.archrag.scmadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.adaptercore.HttpSourceConnector;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Контракт {@code SourceConnector} на заглушке источника: страницы, курсор, GET by id, DELETE, 429. */
class ScmAdapterContractTest {

  private StubSource source;
  private StubSourceServer server;
  private HttpSourceConnector connector;

  @BeforeEach
  void setUp() {
    source = StubSources.scm(Clock.systemUTC());
    server = new StubSourceServer(source);
    connector = new HttpSourceConnector(ScmAdapter.SYSTEM, server.baseUri());
  }

  @AfterEach
  void tearDown() {
    connector.close();
    server.close();
  }

  @Test
  void adapterIsBoundToItsSource() {
    assertThat(connector.system()).isEqualTo(ScmAdapter.SYSTEM);
    assertThat(ScmAdapter.config(server.baseUri(), "secret").sourceUrn())
        .isEqualTo("urn:corp:" + ScmAdapter.SYSTEM.code());
    var foreign = io.github.unlocker.archrag.adaptercore.AdapterConfig.of(
        ScmAdapter.SYSTEM == io.github.unlocker.archrag.sourcespi.SourceSystem.EAM
            ? io.github.unlocker.archrag.sourcespi.SourceSystem.SCM
            : io.github.unlocker.archrag.sourcespi.SourceSystem.EAM,
        server.baseUri(), "secret");
    assertThatThrownBy(() -> ScmAdapter.create(foreign, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotWalksAllObjectsInPagesAndCompletes() {
    ChangePage page = connector.fetchChanges(null, 1);
    int seen = page.changes().size();
    while (page.hasMore()) {
      assertThat(page.snapshotComplete()).isFalse();
      page = connector.fetchChanges(page.nextCursor(), 1);
      seen += page.changes().size();
    }

    assertThat(seen).isEqualTo(2);
    assertThat(page.snapshotComplete()).isTrue();
    assertThat(connector.fetchChanges(page.nextCursor(), 10).changes()).isEmpty();
  }

  @Test
  void incrementalPollingReturnsOnlyChangesAfterCursor() {
    String cursor = connector.fetchChanges(null, 100).nextCursor();
    source.upsert("SERVICE", "svc-payments-api", StubSources.fields("name", "changed"));

    ChangePage page = connector.fetchChanges(cursor, 100);

    assertThat(page.changes()).singleElement().satisfies(c -> {
      assertThat(c.sourceId()).isEqualTo("svc-payments-api");
      assertThat(c.sourceVersion()).isEqualTo(2);
      assertThat(c.payload()).containsEntry("name", "changed");
    });
  }

  @Test
  void fetchByIdReturnsStateAndEmptyForUnknownObject() {
    var found = connector.fetchById("SERVICE", "svc-payments-api").orElseThrow();

    assertThat(found.sourceId()).isEqualTo("svc-payments-api");
    assertThat(found.payload().get("name")).isNotNull();
    assertThat(connector.fetchById("SERVICE", "no-such-object")).isEmpty();
  }

  @Test
  void deleteIsVisibleAsTombstone() {
    String cursor = connector.fetchChanges(null, 100).nextCursor();
    source.delete("SERVICE", "svc-payments-api");

    assertThat(connector.fetchById("SERVICE", "svc-payments-api").orElseThrow().operation())
        .isEqualTo(ChangeOperation.DELETE);
    assertThat(connector.fetchChanges(cursor, 100).changes())
        .singleElement()
        .satisfies(c -> assertThat(c.operation()).isEqualTo(ChangeOperation.DELETE));
  }

  @Test
  void rateLimitCarriesRetryAfterAndLeavesStateIntact() {
    source.failNext(1, 429, Duration.ofSeconds(3));

    assertThatThrownBy(() -> connector.fetchChanges(null, 10))
        .isInstanceOfSatisfying(SourceUnavailableException.class, e -> {
          assertThat(e.isRateLimited()).isTrue();
          assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        });
    assertThat(connector.fetchChanges(null, 10).changes()).hasSize(2);
  }
}
