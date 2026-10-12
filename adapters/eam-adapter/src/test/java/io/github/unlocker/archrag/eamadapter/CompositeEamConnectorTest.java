package io.github.unlocker.archrag.eamadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.adaptercore.HttpSourceConnector;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.EamApiSeed;
import io.github.unlocker.archrag.sourcestubs.EamApiStore;
import io.github.unlocker.archrag.sourcestubs.EamApiStub;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompositeEamConnectorTest {

  private StubSource legacyStub;
  private StubSourceServer legacyServer;
  private EamApiStore store;
  private EamApiStub apiStub;
  private CompositeEamConnector connector;

  @BeforeEach
  void setUp() {
    legacyStub = StubSources.eam(Clock.systemUTC());
    legacyServer = new StubSourceServer(legacyStub);
    store = EamApiSeed.seeded(Clock.systemUTC());
    apiStub = new EamApiStub(store, "tok");
    connector = new CompositeEamConnector(
        new EamApiConnector(apiStub.baseUri(), "tok", 100, d -> {}, () -> 0L),
        new HttpSourceConnector(SourceSystem.EAM, legacyServer.baseUri()));
  }

  @AfterEach
  void tearDown() {
    connector.close();
    legacyServer.close();
    apiStub.close();
  }

  private record Run(List<ChangePage> pages, List<SourceChange> changes) {}

  private Run drain(String cursor, int limit) {
    List<ChangePage> pages = new ArrayList<>();
    List<SourceChange> changes = new ArrayList<>();
    ChangePage p;
    String c = cursor;
    do {
      p = connector.fetchChanges(c, limit);
      pages.add(p);
      changes.addAll(p.changes());
      c = p.nextCursor();
    } while (p.hasMore());
    return new Run(pages, changes);
  }

  private static List<String> types(List<SourceChange> changes) {
    return changes.stream().map(SourceChange::sourceType).distinct().sorted().toList();
  }

  @Test
  void snapshotCoversBothHalvesAndDropsLegacyItSystem() {
    Run run = drain(null, 2);
    assertThat(types(run.changes())).containsExactly("IT_SYSTEM", "PLATFORM", "SERVICE_DEPENDENCY", "SOLUTION", "TEAM");
    // IT_SYSTEM приходит только из eam-api (ключ id), старые строковые ключи отброшены.
    assertThat(run.changes()).filteredOn(c -> c.sourceType().equals("IT_SYSTEM"))
        .extracting(SourceChange::sourceId).containsExactlyInAnyOrder("5101", "5102");
  }

  @Test
  void snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf() {
    Run run = drain(null, 2);
    ChangePage last = run.pages().getLast();
    assertThat(last.snapshotComplete()).isTrue();
    assertThat(last.hasMore()).isFalse();
    assertThat(run.pages().subList(0, run.pages().size() - 1)).allMatch(p -> !p.snapshotComplete() && p.hasMore());
    // Последняя страница содержит легасистые данные, а eam-api-половина закончилась раньше.
    assertThat(types(run.changes())).contains("TEAM", "SERVICE_DEPENDENCY");
    int firstLegacy = indexOfFirst(run.pages(), "TEAM", "SERVICE_DEPENDENCY");
    int lastApi = lastIndexOf(run.pages(), "PLATFORM", "SOLUTION", "IT_SYSTEM");
    assertThat(firstLegacy).isGreaterThan(lastApi);
  }

  private static int indexOfFirst(List<ChangePage> pages, String... types) {
    for (int i = 0; i < pages.size(); i++) {
      for (SourceChange c : pages.get(i).changes()) {
        if (List.of(types).contains(c.sourceType())) {
          return i;
        }
      }
    }
    return -1;
  }

  private static int lastIndexOf(List<ChangePage> pages, String... types) {
    int result = -1;
    for (int i = 0; i < pages.size(); i++) {
      for (SourceChange c : pages.get(i).changes()) {
        if (List.of(types).contains(c.sourceType())) {
          result = i;
        }
      }
    }
    return result;
  }

  @Test
  void incrementalFromSnapshotCursorSeesChangesOfBothHalves() {
    Run snapshot = drain(null, 100);
    String cursor = snapshot.pages().getLast().nextCursor();

    store.upsert("platforms", 3999, Map.of("name", "New Platform"));
    legacyStub.upsert("TEAM", "TEAM-NEW", StubSources.fields("name", "New Team"));

    Run incremental = drain(cursor, 100);
    assertThat(incremental.pages().getLast().snapshotComplete()).isFalse();
    assertThat(incremental.changes()).extracting(c -> c.sourceType() + "/" + c.sourceId())
        .contains("PLATFORM/3999", "TEAM/TEAM-NEW");
    assertThat(incremental.changes()).extracting(SourceChange::sourceType).doesNotContain("IT_SYSTEM");
  }

  @Test
  void incrementalWithoutChangesFinishesWithStableCursor() {
    String cursor = drain(null, 100).pages().getLast().nextCursor();
    Run idle = drain(cursor, 100);
    assertThat(idle.changes()).isEmpty();
    assertThat(idle.pages().getLast().hasMore()).isFalse();
    // Повторный цикл начинается с того же состояния: курсор не уходит и не зацикливается.
    assertThat(drain(idle.pages().getLast().nextCursor(), 100).changes()).isEmpty();
  }

  @Test
  void everyPageWithMoreAdvancesCursor() {
    Run run = drain(null, 1);
    List<String> cursors = run.pages().stream().filter(ChangePage::hasMore).map(ChangePage::nextCursor).toList();
    assertThat(cursors).doesNotHaveDuplicates();
  }

  @Test
  void fetchByIdRoutesByTypeAndKeepsLegacyTeamWebhookWorking() {
    assertThat(connector.fetchById("IT_SYSTEM", "5101")).isPresent();
    assertThat(connector.fetchById("PLATFORM", "3101")).isPresent();
    assertThat(connector.fetchById("TEAM", "TEAM-PAY")).isPresent();
    // Legacy IT_SYSTEM больше не читается: ключа EAM-1042 в EAM нет.
    assertThat(connector.fetchById("IT_SYSTEM", "EAM-1042")).isEmpty();
    assertThat(connector.fetchById("UNKNOWN", "1")).isEmpty();
  }

  @Test
  void legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker() {
    SourceConnector neverComplete = new SourceConnector() {
      @Override public SourceSystem system() { return SourceSystem.EAM; }
      @Override public ChangePage fetchChanges(String cursor, int limit) { return new ChangePage(List.of(), "x", false, false); }
      @Override public Optional<SourceChange> fetchById(String t, String i) { return Optional.empty(); }
    };
    var broken = new CompositeEamConnector(
        new EamApiConnector(apiStub.baseUri(), "tok", 100, d -> {}, () -> 0L), neverComplete);
    ChangePage p = broken.fetchChanges(null, 100);
    while (p.hasMore()) {
      String c = p.nextCursor();
      if (p.nextCursor() == null) {
        break;
      }
      try {
        p = broken.fetchChanges(c, 100);
      } catch (IllegalStateException e) {
        assertThat(e).hasMessageContaining("snapshot-complete");
        return;
      }
    }
    assertThat(p.snapshotComplete()).as("marker must not be set").isFalse();
  }

  @Test
  void foreignCursorOfTheOldAdapterRestartsIncrementalReadWithoutSnapshotMarker() {
    // Checkpoint старого адаптера (до перехода на eam-api) нового формата не имеет: чтение не должно зависнуть.
    Run run = drain("12", 100);
    assertThat(run.pages()).noneMatch(ChangePage::snapshotComplete);
    assertThat(run.pages().getLast().hasMore()).isFalse();
    assertThat(types(run.changes())).contains("IT_SYSTEM", "TEAM");
    // Итоговый курсор нового формата: следующий цикл идёт обычным инкрементом.
    assertThat(drain(run.pages().getLast().nextCursor(), 100).changes()).isEmpty();
  }

  @Test
  void upstreamPageWithoutCursorIsAnError() {
    SourceConnector noCursor = new SourceConnector() {
      @Override public SourceSystem system() { return SourceSystem.EAM; }
      @Override public ChangePage fetchChanges(String cursor, int limit) { return new ChangePage(List.of(), null, false, false); }
      @Override public Optional<SourceChange> fetchById(String t, String i) { return Optional.empty(); }
    };
    var broken = new CompositeEamConnector(noCursor, noCursor);
    assertThatThrownBy(() -> broken.fetchChanges(null, 10)).isInstanceOf(IllegalStateException.class);
  }
}
