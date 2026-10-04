package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.adaptercore.WebhookServer;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import io.github.unlocker.archrag.sourcestubs.StubWebhookSender;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * eam-адаптер на реальных PostgreSQL и S3: подписанный webhook от заглушки превращается в строку
 * {@code RECEIVED} в {@code inbox_event} и объект в S3; повтор не создаёт вторую строку; polling
 * заканчивает snapshot маркером.
 */
@Testcontainers
class AdapterInboxIT {

  private static final String SECRET = "it-webhook-secret";
  private static final String SOURCE = "urn:corp:eam";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  private static S3RawPayloadStore store;
  private static StubSource eam;
  private static StubSourceServer sourceServer;
  private static SourceAdapter adapter;
  private static WebhookServer webhookServer;
  private static StubWebhookSender sender;

  @BeforeAll
  static void start() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    DataSource dataSource = ds;
    JournalMigrations.apply(dataSource);
    store = S3RawPayloadStore.create(
        URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
        ContainersSmokeIT.S3_ACCESS_KEY, ContainersSmokeIT.S3_SECRET_KEY, "adapter-it-bucket");
    store.ensureBucket();
    eam = StubSources.eam(Clock.systemUTC());
    sourceServer = new StubSourceServer(eam);
    adapter = EamAdapter.create(EamAdapter.config(sourceServer.baseUri(), SECRET),
        new PostgresEventJournal(dataSource), store);
    webhookServer = adapter.startWebhook(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    sender = new StubWebhookSender(webhookServer.endpoint(), SECRET, Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    sender.close();
    adapter.close();
    sourceServer.close();
    store.close();
  }

  private static String scalar(String sql, String... args) throws Exception {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setString(i + 1, args[i]);
      }
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  @Test
  void webhookIsStoredAsReceivedRowWithRawObjectAndDuplicateAddsNothing() throws Exception {
    eam.upsert(StubSources.IT_SYSTEM, "EAM-9001", StubSources.fields("name", "Webhook System"));
    WebhookEvent event = eam.webhookEvents().getLast();

    assertThat(sender.send(event)).isEqualTo(202);

    assertThat(scalar("select status from inbox_event where source = ? and event_id = ?", SOURCE,
        event.eventId())).isEqualTo(ProcessingStatus.RECEIVED.name());
    String key = scalar("select payload_key from inbox_event where source = ? and event_id = ?",
        SOURCE, event.eventId());
    assertThat(key).isNotNull();
    var ref = new io.github.unlocker.archrag.eventschemas.RawPayloadRef(key,
        scalar("select payload_hash from inbox_event where source = ? and event_id = ?", SOURCE,
            event.eventId()));
    assertThat(new String(store.get(ref), java.nio.charset.StandardCharsets.UTF_8))
        .contains("Webhook System");

    assertThat(sender.send(event)).isEqualTo(202);

    assertThat(scalar("select count(*) from inbox_event where source = ? and event_id = ?", SOURCE,
        event.eventId())).isEqualTo("1");
  }

  @Test
  void badSignatureAndStaleTimestampAreRejectedAndNothingIsWritten() throws Exception {
    eam.upsert(StubSources.IT_SYSTEM, "EAM-9002", StubSources.fields("name", "Rejected"));
    WebhookEvent event = eam.webhookEvents().getLast();

    assertThat(sender.sendWithBadSignature(event)).isEqualTo(401);
    assertThat(sender.sendAt(event, Instant.now().minusSeconds(3600))).isEqualTo(401);

    assertThat(scalar("select count(*) from inbox_event where source = ? and event_id = ?", SOURCE,
        event.eventId())).isEqualTo("0");
  }

  @Test
  void snapshotEndsWithMarkerOnRealInbox() throws Exception {
    PollResult result = adapter.poller().pollOnce();

    assertThat(result.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    String runId = result.syncRunId();
    assertThat(scalar("select type from inbox_event where source = ? and event_id = ?", SOURCE,
        "snapshot-complete:" + runId)).isEqualTo("architecture.sync.snapshot-complete.v1");
    // Все события snapshot принадлежат одному прогону и записаны до маркера.
    assertThat(scalar("select count(*) from inbox_event where source = ? and sync_run_id = ?",
        SOURCE, runId)).isEqualTo(Long.toString(result.appended() + 1));
    assertThat(scalar("select count(*) from inbox_event where sync_run_id = ? and received_at > "
        + "(select received_at from inbox_event where event_id = ?)", runId,
        "snapshot-complete:" + runId)).isEqualTo("0");
    assertThat(scalar("select cursor from consumer_checkpoint where consumer = 'eam-poller' and source = ?",
        SOURCE)).startsWith("i");
  }
}
