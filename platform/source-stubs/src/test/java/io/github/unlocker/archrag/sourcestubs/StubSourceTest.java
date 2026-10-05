package io.github.unlocker.archrag.sourcestubs;

import static io.github.unlocker.archrag.sourcestubs.StubSources.fields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubSourceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void seededSourcesCoverTheVerticalSlice() {
    Map<SourceSystem, StubSource> all = StubSources.seeded(clock);
    assertThat(all).containsOnlyKeys(SourceSystem.values());
    assertThat(all.get(SourceSystem.EAM).fetchById("IT_SYSTEM", "EAM-1042")).isPresent();
    assertThat(all.get(SourceSystem.SCM).fetchById("SERVICE", "svc-payments-api")).isPresent();
    assertThat(all.get(SourceSystem.CMDB).fetchById("COMPUTE_INSTANCE", "vm-pay-01")).isPresent();
    SourceChange deployment =
        all.get(SourceSystem.DEPLOY_MAP).fetchById(DeployMapFormat.DEPLOYMENT,
            "dep-payments-api-prod").orElseThrow();
    assertThat(deployment.payload()).containsEntry("format", DeployMapFormat.FORMAT);
  }

  @Test
  void updateBumpsVersionMonotonically() {
    StubSource eam = StubSources.eam(clock);
    long v1 = eam.fetchById("IT_SYSTEM", "EAM-1042").orElseThrow().sourceVersion();
    SourceChange updated = eam.upsert("IT_SYSTEM", "EAM-1042", fields("name", "Payments Core v2"));
    assertThat(updated.sourceVersion()).isEqualTo(v1 + 1);
    assertThat(eam.fetchById("IT_SYSTEM", "EAM-1042").orElseThrow().payload())
        .containsEntry("name", "Payments Core v2");
  }

  @Test
  void deleteIsVisibleByIdAndIncrementallyButNotInSnapshot() {
    StubSource eam = StubSources.eam(clock);
    ChangePage snapshot = eam.fetchChanges(null, 100);
    String checkpoint = snapshot.nextCursor();
    SourceChange deleted = eam.delete("IT_SYSTEM", "EAM-2001");

    assertThat(eam.fetchById("IT_SYSTEM", "EAM-2001")).contains(deleted);
    assertThat(deleted.operation()).isEqualTo(ChangeOperation.DELETE);
    assertThat(eam.fetchChanges(checkpoint, 100).changes()).containsExactly(deleted);
    assertThat(eam.fetchChanges(null, 100).changes()).extracting(SourceChange::sourceId)
        .doesNotContain("EAM-2001");
  }

  @Test
  void snapshotPagesAreStableAndOnlyLastIsComplete() {
    StubSource eam = StubSources.eam(clock);
    List<SourceChange> all = new ArrayList<>();
    List<ChangePage> pages = new ArrayList<>();
    String cursor = null;
    do {
      ChangePage page = eam.fetchChanges(cursor, 2);
      pages.add(page);
      all.addAll(page.changes());
      // Изменение посреди прохода не попадает в этот snapshot.
      if (pages.size() == 1) {
        eam.upsert("IT_SYSTEM", "EAM-9999", fields("name", "late"));
      }
      cursor = page.nextCursor();
      if (!page.hasMore()) {
        break;
      }
    } while (true);

    assertThat(all).extracting(SourceChange::sourceId).containsExactlyInAnyOrder("TEAM-PAY",
        "EAM-2001", "EAM-1042", "dep-payments-api-ledger-api");
    assertThat(pages).hasSize(2);
    assertThat(pages.get(0).snapshotComplete()).isFalse();
    assertThat(pages.get(1).snapshotComplete()).isTrue();
    // Позже созданный объект приходит инкрементально с курсором snapshot.
    assertThat(eam.fetchChanges(cursor, 10).changes()).extracting(SourceChange::sourceId)
        .containsExactly("EAM-9999");
  }

  @Test
  void incrementalPollingPagesThroughJournal() {
    StubSource cmdb = StubSources.cmdb(clock);
    String cursor = cmdb.fetchChanges(null, 10).nextCursor();
    cmdb.upsert("COMPUTE_INSTANCE", "vm-pay-01", fields("state", "STOPPED"));
    cmdb.upsert("COMPUTE_INSTANCE", "vm-pay-02", fields("state", "RUNNING"));

    ChangePage first = cmdb.fetchChanges(cursor, 1);
    assertThat(first.changes()).hasSize(1);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.snapshotComplete()).isFalse();
    ChangePage second = cmdb.fetchChanges(first.nextCursor(), 1);
    assertThat(second.hasMore()).isFalse();
    assertThat(cmdb.fetchChanges(second.nextCursor(), 1).changes()).isEmpty();
  }

  @Test
  void partialRecordIsMarkedAndCarriesNullFields() {
    StubSource eam = StubSources.eam(clock);
    SourceChange partial = eam.upsertPartial("IT_SYSTEM", "EAM-1042", fields("ownerTeam", null));
    assertThat(partial.completeness()).isEqualTo(Completeness.PARTIAL);
    assertThat(partial.payload()).containsKey("ownerTeam").doesNotContainKey("name");
    assertThat(partial.payload().get("ownerTeam")).isNull();
  }

  @Test
  void brokenReferencePointsToUnknownSystem() {
    StubSource scm = StubSources.scm(clock);
    StubSource eam = StubSources.eam(clock);
    String id = StubSources.addServiceWithBrokenReference(scm);
    Object systemCode = scm.fetchById("SERVICE", id).orElseThrow().payload().get("systemCode");
    assertThat(eam.fetchById("IT_SYSTEM", (String) systemCode)).isEmpty();
  }

  @Test
  void suppressedWebhookIsMissingFromNotificationsButPresentInPolling() {
    StubSource eam = StubSources.eam(clock);
    int before = eam.webhookEvents().size();
    String cursor = eam.fetchChanges(null, 100).nextCursor();
    eam.suppressWebhooks(true);
    eam.upsert("IT_SYSTEM", "EAM-1042", fields("name", "missed"));
    eam.suppressWebhooks(false);
    eam.upsert("IT_SYSTEM", "EAM-2001", fields("name", "seen"));

    List<WebhookEvent> events = eam.webhookEvents();
    assertThat(events).hasSize(before + 1);
    assertThat(events).extracting(WebhookEvent::sourceId).doesNotContain("missed")
        .endsWith("EAM-2001");
    assertThat(eam.fetchChanges(cursor, 10).changes()).extracting(SourceChange::sourceId)
        .containsExactly("EAM-1042", "EAM-2001");
  }

  @Test
  void staleWebhookVersionIsLowerThanCurrentState() {
    StubSource eam = StubSources.eam(clock);
    eam.upsert("IT_SYSTEM", "EAM-1042", fields("name", "v2"));
    List<WebhookEvent> events = eam.webhookEvents().stream().filter(e -> e.sourceId()
        .equals("EAM-1042")).toList();
    assertThat(events).hasSize(2);
    long current = eam.fetchById("IT_SYSTEM", "EAM-1042").orElseThrow().sourceVersion();
    // out-of-order: старое уведомление доставлено после нового.
    assertThat(events.get(0).sourceVersion()).isLessThan(events.get(1).sourceVersion())
        .isLessThan(current + 1);
    assertThat(events).extracting(WebhookEvent::eventId).doesNotHaveDuplicates();
  }

  @Test
  void rateLimitThenRecovery() {
    StubSource eam = StubSources.eam(clock);
    eam.failNext(1, 429, Duration.ofSeconds(7));
    assertThatThrownBy(() -> eam.fetchChanges(null, 10))
        .isInstanceOfSatisfying(SourceUnavailableException.class, e -> {
          assertThat(e.isRateLimited()).isTrue();
          assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        });
    assertThat(eam.fetchChanges(null, 10).changes()).isNotEmpty();
  }

  @Test
  void serverErrorAffectsFetchByIdAndCountsDown() {
    StubSource eam = StubSources.eam(clock);
    eam.failNext(2, 503, null);
    assertThatThrownBy(() -> eam.fetchById("IT_SYSTEM", "EAM-1042"))
        .isInstanceOfSatisfying(SourceUnavailableException.class, e -> assertThat(e.statusCode())
            .isEqualTo(503));
    assertThatThrownBy(() -> eam.fetchChanges(null, 10))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(eam.fetchById("IT_SYSTEM", "EAM-1042")).isPresent();
  }

  @Test
  void reorderedPageHasDuplicateAndReversedVersions() {
    StubSource eam = StubSources.eam(clock);
    String cursor = eam.fetchChanges(null, 100).nextCursor();
    eam.upsert("IT_SYSTEM", "EAM-1042", fields("name", "v2"));
    eam.upsert("IT_SYSTEM", "EAM-1042", fields("name", "v3"));
    eam.reorderNextPage();

    ChangePage page = eam.fetchChanges(cursor, 10);
    assertThat(page.changes()).extracting(SourceChange::sourceVersion).containsExactly(3L, 2L, 2L);
    // Один раз: следующий вызов с тем же курсором приходит в нормальном порядке.
    assertThat(eam.fetchChanges(cursor, 10).changes())
        .extracting(SourceChange::sourceVersion).containsExactly(2L, 3L);
  }

  @Test
  void invalidLimitIsRejected() {
    StubSource eam = StubSources.eam(clock);
    assertThatThrownBy(() -> eam.fetchChanges(null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownObjectIsEmpty() {
    assertThat(StubSources.eam(clock).fetchById("IT_SYSTEM", "nope")).isEmpty();
  }
}
