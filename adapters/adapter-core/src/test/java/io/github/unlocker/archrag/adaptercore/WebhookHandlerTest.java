package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcespi.WebhookSignature;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookHandlerTest {

  private static final String SECRET = "top-secret-value";
  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final InMemoryJournal journal = new InMemoryJournal();
  private final InMemoryRawStore raw = new InMemoryRawStore();
  private StubSource eam;
  private WebhookHandler handler;

  @BeforeEach
  void setUp() {
    eam = StubSources.eam(clock);
    AdapterConfig config = AdapterConfig.of(SourceSystem.EAM, URI.create("http://unused"), SECRET);
    handler = new WebhookHandler(config, eam, journal, raw, clock);
  }

  private WebhookEvent lastEvent() {
    return eam.webhookEvents().getLast();
  }

  private int deliver(WebhookEvent e, long timestamp, String signSecret, String eventIdHeader) {
    String body = body(e);
    Map<String, String> h = new HashMap<>();
    h.put(WebhookSignature.TIMESTAMP_HEADER.toLowerCase(), Long.toString(timestamp));
    h.put(WebhookSignature.SIGNATURE_HEADER.toLowerCase(),
        WebhookSignature.sign(signSecret, timestamp, body));
    h.put(WebhookSignature.EVENT_ID_HEADER.toLowerCase(), eventIdHeader);
    return handler.handle(name -> h.get(name.toLowerCase()), body);
  }

  private int deliver(WebhookEvent e) {
    return deliver(e, NOW.getEpochSecond(), SECRET, e.eventId());
  }

  private static String body(WebhookEvent e) {
    return Json.write(Map.of("eventId", e.eventId(), "source", e.source(), "sourceType",
        e.sourceType(), "sourceId", e.sourceId(), "sourceVersion", e.sourceVersion(), "operation",
        e.operation(), "occurredAt", e.occurredAt()));
  }

  @Test
  void validWebhookIsRecordedBeforeAccepted() {
    WebhookEvent e = lastEvent();

    assertThat(deliver(e)).isEqualTo(202);

    var row = journal.find("urn:corp:eam", e.eventId()).orElseThrow();
    assertThat(row.status()).isEqualTo(ProcessingStatus.RECEIVED);
    assertThat(row.sourceId()).isEqualTo(e.sourceId());
    assertThat(row.payloadRef()).isNotNull();
    assertThat(raw.objects).containsKey(row.payloadRef().key());
  }

  @Test
  void repeatedWebhookIsDuplicateAndWritesNoSecondRow() {
    WebhookEvent e = lastEvent();

    assertThat(deliver(e)).isEqualTo(202);
    assertThat(deliver(e)).isEqualTo(202);

    assertThat(journal.rows).hasSize(1);
    assertThat(journal.append(journal.eventList().getFirst(), null, null).status())
        .isEqualTo(ProcessingStatus.DUPLICATE);
  }

  @Test
  void badSignatureIsRejectedAndNothingIsStored() {
    WebhookEvent e = lastEvent();

    assertThat(deliver(e, NOW.getEpochSecond(), SECRET + "-wrong", e.eventId())).isEqualTo(401);

    assertThat(journal.rows).isEmpty();
    assertThat(raw.objects).isEmpty();
  }

  @Test
  void timestampOutsideReplayWindowIsRejected() {
    WebhookEvent e = lastEvent();
    long old = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();
    long future = NOW.plus(Duration.ofMinutes(6)).getEpochSecond();

    assertThat(deliver(e, old, SECRET, e.eventId())).isEqualTo(401);
    assertThat(deliver(e, future, SECRET, e.eventId())).isEqualTo(401);
    assertThat(deliver(e, NOW.minus(Duration.ofMinutes(4)).getEpochSecond(), SECRET, e.eventId()))
        .isEqualTo(202);
    assertThat(journal.rows).hasSize(1);
  }

  @Test
  void missingHeadersAreMalformed() {
    assertThat(handler.handle(name -> null, "{}")).isEqualTo(401);
  }

  @Test
  void unsignedEventIdHeaderMustAgreeWithSignedBody() {
    WebhookEvent e = lastEvent();

    assertThat(deliver(e, NOW.getEpochSecond(), SECRET, "forged-event-id")).isEqualTo(400);
    assertThat(journal.rows).isEmpty();
  }

  @Test
  void foreignSourceAndBrokenBodyAreBadRequests() {
    WebhookEvent e = lastEvent();
    WebhookEvent foreign = new WebhookEvent(e.eventId(), "scm", e.sourceType(), e.sourceId(),
        e.sourceVersion(), ChangeOperation.UPSERT, e.occurredAt());
    assertThat(deliver(foreign)).isEqualTo(400);

    String broken = "not json";
    Map<String, String> h = Map.of(
        WebhookSignature.TIMESTAMP_HEADER, Long.toString(NOW.getEpochSecond()),
        WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(SECRET, NOW.getEpochSecond(), broken));
    assertThat(handler.handle(h::get, broken)).isEqualTo(400);
  }

  @Test
  void sourceOutageAnswersServiceUnavailableAndStoresNothing() {
    WebhookEvent e = lastEvent();
    eam.failNext(1, 429, Duration.ofSeconds(1));

    assertThat(deliver(e)).isEqualTo(503);
    assertThat(journal.rows).isEmpty();

    // Источник восстановился: повторная доставка принимается.
    assertThat(deliver(e)).isEqualTo(202);
  }

  @Test
  void unknownObjectAnswersNotFound() {
    WebhookEvent ghost = new WebhookEvent("eam-evt-999", "eam", "IT_SYSTEM", "NOPE", 1,
        ChangeOperation.UPSERT, NOW);

    assertThat(deliver(ghost)).isEqualTo(404);
    assertThat(journal.rows).isEmpty();
  }

  @Test
  void journalFailureIsNotAcknowledged() {
    journal.failAppend = new IllegalStateException("db down");

    assertThatThrownBy(() -> deliver(lastEvent())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void deleteNotificationRecordsTombstoneEvent() {
    eam.delete(StubSources.IT_SYSTEM, "EAM-2001");
    WebhookEvent e = lastEvent();
    assertThat(e.operation()).isEqualTo(ChangeOperation.DELETE);

    assertThat(deliver(e)).isEqualTo(202);

    assertThat(journal.eventList().getFirst().type()).isEqualTo(EventMapper.TYPE_ASSET_DELETED);
  }

  @Test
  void deleteNotificationForForgottenObjectIsRecordedFromSignedBody() {
    WebhookEvent gone = new WebhookEvent("eam-evt-777", "eam", "IT_SYSTEM", "GONE", 5,
        ChangeOperation.DELETE, NOW);

    assertThat(deliver(gone)).isEqualTo(202);

    var e = journal.eventList().getFirst();
    assertThat(e.type()).isEqualTo(EventMapper.TYPE_ASSET_DELETED);
    assertThat(e.data().sourceVersion().value()).isEqualTo("5");
    assertThat(deliver(gone)).isEqualTo(202);
    assertThat(journal.rows).hasSize(1);
  }
}
