package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpSourceConnectorTest {

  private StubSource eam;
  private StubSourceServer server;
  private HttpSourceConnector connector;

  @BeforeEach
  void setUp() {
    eam = StubSources.eam(Clock.systemUTC());
    server = new StubSourceServer(eam);
    connector = new HttpSourceConnector(SourceSystem.EAM, server.baseUri());
  }

  @AfterEach
  void tearDown() {
    connector.close();
    server.close();
  }

  @Test
  void snapshotPagesCarryCursorAndFinishWithSnapshotComplete() {
    ChangePage first = connector.fetchChanges(null, 2);
    assertThat(first.changes()).hasSize(2);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.snapshotComplete()).isFalse();

    ChangePage last = connector.fetchChanges(first.nextCursor(), 2);
    assertThat(last.changes()).hasSize(2);
    assertThat(last.snapshotComplete()).isTrue();
    assertThat(connector.fetchChanges(last.nextCursor(), 2).changes()).isEmpty();
  }

  @Test
  void fetchByIdReturnsStateAndEmptyForUnknown() {
    var found = connector.fetchById("IT_SYSTEM", "EAM-1042").orElseThrow();
    assertThat(found.payload()).containsEntry("name", "Payments Core");
    assertThat(found.completeness()).isEqualTo(Completeness.COMPLETE);
    assertThat(connector.fetchById("IT_SYSTEM", "NOPE")).isEmpty();
  }

  @Test
  void deleteIsVisibleThroughBothEndpoints() {
    String cursor = connector.fetchChanges(null, 10).nextCursor();
    eam.delete("IT_SYSTEM", "EAM-2001");

    assertThat(connector.fetchById("IT_SYSTEM", "EAM-2001").orElseThrow().operation())
        .isEqualTo(ChangeOperation.DELETE);
    assertThat(connector.fetchChanges(cursor, 10).changes())
        .singleElement()
        .satisfies(c -> assertThat(c.operation()).isEqualTo(ChangeOperation.DELETE));
  }

  @Test
  void partialRecordKeepsNullValues() {
    eam.upsertPartial("IT_SYSTEM", "EAM-1042", StubSources.fields("name", null));

    var change = connector.fetchById("IT_SYSTEM", "EAM-1042").orElseThrow();

    assertThat(change.completeness()).isEqualTo(Completeness.PARTIAL);
    assertThat(change.payload()).containsEntry("name", null);
  }

  @Test
  void rateLimitIsReportedWithRetryAfter() {
    eam.failNext(1, 429, Duration.ofSeconds(7));

    assertThatThrownBy(() -> connector.fetchChanges(null, 10))
        .isInstanceOfSatisfying(SourceUnavailableException.class, e -> {
          assertThat(e.isRateLimited()).isTrue();
          assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        });
    assertThat(connector.fetchChanges(null, 10).changes()).isNotEmpty();
  }

  @Test
  void serverErrorAndUnreachableSourceAreUnavailable() {
    eam.failNext(1, 503, null);
    assertThatThrownBy(() -> connector.fetchById("IT_SYSTEM", "EAM-1042"))
        .isInstanceOfSatisfying(SourceUnavailableException.class,
            e -> assertThat(e.statusCode()).isEqualTo(503));

    server.close();
    assertThatThrownBy(() -> connector.fetchChanges(null, 10))
        .isInstanceOfSatisfying(SourceUnavailableException.class,
            e -> assertThat(e.statusCode()).isZero());
  }
}
