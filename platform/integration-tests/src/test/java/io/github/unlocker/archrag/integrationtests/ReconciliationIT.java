package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.AdapterConfig;
import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.Poller;
import io.github.unlocker.archrag.adaptercore.RetryPolicy;
import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.Reconciler;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.normalizer.Normalizer;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Reconciliation на реальных PostgreSQL и Neo4j: заглушка источника, {@link Poller}, journal, projector и
 * {@link Reconciler} как триггер маркера. Критерий 2: пропущенное удаление и создание исправляются
 * полным snapshot; падение на середине snapshot ничего не удаляет.
 */
@Testcontainers
class ReconciliationIT {

  private static final String SOURCE = "urn:corp:eam";

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static RecordingJournal journal;
  /** Журнал для Reconciler: его tombstone-строки не должны попадать в ручной прогон {@link RecordingJournal}. */
  static PostgresEventJournal pgJournal;
  static EventProcessor processor;

  private StubSource eam;
  private Poller poller;
  private int runs;

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    pgJournal = new PostgresEventJournal(ds);
    journal = new RecordingJournal(pgJournal);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor = new EventProcessor(journal, Normalizer.standard(projector::isActive),
        new PostgresIdentityMapping(ds), projector, new Reconciler(pgJournal, new NoopRawStore(), projector, Clock.systemUTC()));
  }

  @BeforeEach
  void reset() throws Exception {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    // Источник один и тот же (urn:corp:eam), поэтому журнал и checkpoint-ы чистятся между тестами.
    try (var c = java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement()) {
      st.execute("TRUNCATE dlq_entry, inbox_event, consumer_checkpoint");
    }
    journal.appended.clear();
    eam = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    poller = pollerOver(eam);
    journal.pending.clear();
  }

  private Poller pollerOver(SourceConnector connector) {
    var config = new AdapterConfig(SourceSystem.EAM, URI.create("http://unused"), "s", Duration.ofMinutes(5), 2,
        Duration.ofSeconds(1), Duration.ofHours(1), new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2)));
    String prefix = UUID.randomUUID().toString().substring(0, 8);
    return new Poller(config, connector, journal, new NoopRawStore(), Clock.systemUTC(),
        d -> {}, new java.util.Random(1), () -> prefix + "-run-" + (++runs));
  }

  /** Проводит через projector всё, что адаптер записал в inbox, в порядке записи. */
  private void drain() {
    for (var recorded : new ArrayList<>(journal.pending)) {
      processor.process(recorded.event(), recorded.ref());
    }
    journal.pending.clear();
  }

  private static String uid() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private Boolean active(String id) {
    var rows = driver.executableQuery(
            "MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: $id}) RETURN r.active AS a")
        .withParameters(Map.of("id", id)).execute().records();
    return rows.isEmpty() ? null : rows.getFirst().get("a").asBoolean();
  }

  private ProcessingStatus statusOf(String eventIdPrefix, String sourceId) {
    return journal.statuses().stream()
        .filter(e -> e.eventId().startsWith(eventIdPrefix) && e.sourceId().equals(sourceId))
        .map(JournalEntry::status).reduce((a, b) -> b).orElseThrow();
  }

  private static Map<String, Object> team(String name) {
    return Map.of("name", name);
  }

  @Test
  void missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive() {
    String keep = "keep-" + uid();
    String gone = "gone-" + uid();
    eam.upsert("TEAM", keep, team("Keep"));
    eam.upsert("TEAM", gone, team("Gone"));
    poller.pollOnce();
    drain();
    assertThat(active(keep)).isTrue();
    assertThat(active(gone)).isTrue();

    // Удаление без webhook: адаптер о нём не знает, пока не придёт полный snapshot.
    eam.suppressWebhooks(true);
    eam.delete("TEAM", gone);
    PollResult r = poller.snapshotOnce();
    drain();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(active(gone)).isFalse();
    assertThat(active(keep)).isTrue();
    // Нетронутый объект во втором прогоне закончился DUPLICATE (та же версия), но не удалён.
    assertThat(statusOf("snap:" + r.syncRunId(), keep)).isEqualTo(ProcessingStatus.DUPLICATE);
  }

  @Test
  void missedCreateAppearsAfterSnapshot() {
    String first = "first-" + uid();
    String late = "late-" + uid();
    eam.upsert("TEAM", first, team("First"));
    poller.pollOnce();
    drain();

    eam.suppressWebhooks(true);
    eam.upsert("TEAM", late, team("Late"));
    poller.snapshotOnce();
    drain();

    assertThat(active(late)).isTrue();
    assertThat(active(first)).isTrue();
  }

  @Test
  void failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun() {
    String a = "a-" + uid();
    String b = "b-" + uid();
    String c = "c-" + uid();
    eam.upsert("TEAM", a, team("A"));
    eam.upsert("TEAM", b, team("B"));
    eam.upsert("TEAM", c, team("C"));
    eam.upsert("TEAM", "d-" + uid(), team("D"));
    poller.pollOnce();
    drain();
    eam.delete("TEAM", c);

    PollResult failed = pollerOver(new FailingAfter(eam, 1)).snapshotOnce();
    drain();

    assertThat(failed.outcome()).isEqualTo(PollResult.Outcome.GAVE_UP);
    assertThat(active(c)).isTrue();
    assertThat(journal.statuses()).noneMatch(e -> e.eventId().equals("snapshot-complete:" + failed.syncRunId()));

    // Рестарт: новый Poller над тем же журналом продолжает тот же syncRunId (он хранится в checkpoint).
    PollResult done = pollerOver(eam).snapshotOnce();
    drain();

    assertThat(done.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(done.syncRunId()).isEqualTo(failed.syncRunId());
    assertThat(active(c)).isFalse();
    assertThat(active(a)).isTrue();
    assertThat(active(b)).isTrue();
  }

  @Test
  void quarantinedSnapshotObjectIsNotTreatedAsMissing() {
    String id = "q-" + uid();
    eam.upsert("TEAM", id, team("Q"));
    poller.pollOnce();
    drain();
    // Новая версия без обязательного поля: нормализатор отправит событие в карантин, но объект в источнике есть.
    eam.upsert("TEAM", id, Map.of());

    PollResult r = poller.snapshotOnce();
    drain();

    assertThat(statusOf("snap:" + r.syncRunId(), id)).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(active(id)).isTrue();
  }

  @Test
  void reprocessingTheSameMarkerChangesNothing() {
    String gone = "gone-" + uid();
    eam.upsert("TEAM", gone, team("Gone"));
    poller.pollOnce();
    drain();
    eam.delete("TEAM", gone);
    PollResult r = poller.snapshotOnce();
    drain();
    assertThat(active(gone)).isFalse();
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    var reconciler = new Reconciler(pgJournal, new NoopRawStore(), projector, Clock.systemUTC());

    var again = reconciler.reconcile(SOURCE, r.syncRunId(), "snapshot-complete:" + r.syncRunId(), 0);

    assertThat(again.tombstoned()).isZero();
    assertThat(active(gone)).isFalse();
  }

  @Test
  void objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun() {
    String a = "a-" + uid();
    String fresh = "fresh-" + uid();
    eam.upsert("TEAM", a, team("A"));
    eam.upsert("TEAM", "b-" + uid(), team("B"));
    eam.upsert("TEAM", "c-" + uid(), team("C"));
    poller.pollOnce();
    drain();

    // Snapshot начался (первая страница записана), затем пришёл webhook о новом объекте, которого snapshot не видел.
    PollResult failed = pollerOver(new FailingAfter(eam, 1)).snapshotOnce();
    assertThat(failed.outcome()).isEqualTo(PollResult.Outcome.GAVE_UP);
    String webhookEvent = "webhook-" + uid();
    var event = new CanonicalEvent(webhookEvent, SOURCE, CanonicalEvent.TYPE_ASSET_UPSERTED, "team/" + fresh,
        Instant.now(), "urn:corp:schema:asset-upserted:1", null,
        new AssetEventData("TEAM", fresh, new SourceVersion("1"), Map.of("name", "Fresh")));
    var ref = new RawPayloadRef("raw/w", "sha256:w");
    journal.append(event, ref, null);
    processor.process(event, ref);
    assertThat(active(fresh)).isTrue();

    PollResult done = pollerOver(eam).snapshotOnce();
    drain();

    assertThat(done.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(active(fresh)).isTrue();
    assertThat(active(a)).isTrue();
  }

  /** Отдаёт {@code okCalls} страниц, дальше всегда 503. */
  private static final class FailingAfter implements SourceConnector {
    private final StubSource delegate;
    private int okCalls;

    FailingAfter(StubSource delegate, int okCalls) {
      this.delegate = delegate;
      this.okCalls = okCalls;
    }

    @Override
    public SourceSystem system() {
      return delegate.system();
    }

    @Override
    public ChangePage fetchChanges(String cursor, int limit) {
      if (okCalls-- <= 0) {
        throw new SourceUnavailableException(SourceSystem.EAM, 503, null);
      }
      return delegate.fetchChanges(cursor, limit);
    }

    @Override
    public Optional<SourceChange> fetchById(String type, String id) {
      return delegate.fetchById(type, id);
    }
  }

  private static final class NoopRawStore implements RawPayloadStore {
    @Override
    public RawPayloadRef put(String source, byte[] content) {
      return new RawPayloadRef("raw/" + UUID.nameUUIDFromBytes(content), "sha256:" + UUID.nameUUIDFromBytes(content));
    }

    @Override
    public byte[] get(RawPayloadRef ref) {
      throw new UnsupportedOperationException();
    }
  }

  /** Журнал PostgreSQL, который запоминает добавленные события для ручного прогона через projector. */
  static final class RecordingJournal implements EventJournal {
    record Recorded(CanonicalEvent event, RawPayloadRef ref) {}

    private final PostgresEventJournal delegate;
    final List<Recorded> pending = new ArrayList<>();
    final List<JournalEntry> appended = new ArrayList<>();

    RecordingJournal(PostgresEventJournal delegate) {
      this.delegate = delegate;
    }

    /** Актуальные записи всех добавленных событий. */
    List<JournalEntry> statuses() {
      return appended.stream().map(e -> delegate.find(e.source(), e.eventId()).orElseThrow()).toList();
    }

    @Override
    public JournalEntry append(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId) {
      JournalEntry entry = delegate.append(event, payloadRef, syncRunId);
      appended.add(entry);
      if (entry.status() != ProcessingStatus.DUPLICATE) {
        pending.add(new Recorded(event, payloadRef));
      }
      return entry;
    }

    @Override
    public Optional<JournalEntry> find(String source, String eventId) {
      return delegate.find(source, eventId);
    }

    @Override
    public JournalEntry transition(String s, String id, ProcessingStatus st, String c, String r) {
      return delegate.transition(s, id, st, c, r);
    }

    @Override
    public JournalEntry toDlq(String s, String id, String c, String r) {
      return delegate.toDlq(s, id, c, r);
    }

    @Override
    public Optional<Checkpoint> loadCheckpoint(String consumer, String source) {
      return delegate.loadCheckpoint(consumer, source);
    }

    @Override
    public Checkpoint saveCheckpoint(String consumer, String source, String cursor) {
      return delegate.saveCheckpoint(consumer, source, cursor);
    }

    @Override
    public SnapshotContents snapshotContents(String source, String syncRunId) {
      return delegate.snapshotContents(source, syncRunId);
    }

    @Override
    public Set<ObjectRef> objectsReceivedSince(String source, Instant since) {
      return delegate.objectsReceivedSince(source, since);
    }
  }
}
