package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.ingestionservice.StoredEventReader;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Контракт: событие, собранное {@link StoredEventReader} из строки журнала и raw, равно событию, которое адаптер
 * построил {@code EventMapper.toEvent} из того же изменения. Тест лежит в пакете адаптера, потому что {@code InboxWriter.rawJson}
 * (формат raw) виден только ему.
 */
class StoredEventReaderContractTest {

  private static final Instant T = Instant.parse("2026-10-01T10:15:30.123Z");
  private final StoredEventReader reader = new StoredEventReader(JsonMapper.builder().build());

  private static Map<String, Object> payload() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("name", "Pay \"core\"\nline2 é中");
    p.put("tier", 2L);
    p.put("ratio", 0.5);
    p.put("active", true);
    p.put("tags", List.of("a", "b"));
    p.put("nested", Map.of("k", 7L));
    p.put("absent", null);
    return p;
  }

  private static CanonicalEvent viaAdapter(SourceChange change, String id, String corr) {
    return EventMapper.toEvent(SourceSystem.EAM, id, change, corr);
  }

  private CanonicalEvent viaReader(SourceChange change, String id, String corr, String type, String schema) {
    CanonicalEvent adapter = viaAdapter(change, id, corr);
    JournalEntry entry = new JournalEntry(
        adapter.source(), id, corr, "run-1", change.sourceType(), change.sourceId(),
        new SourceVersion(Long.toString(change.sourceVersion())), schema, ProcessingStatus.PROJECTED, null, null, 0,
        new RawPayloadRef("raw/x", "h"), T, T);
    byte[] raw = InboxWriter.rawJson(change).getBytes(StandardCharsets.UTF_8);
    return reader.toEvent(new StoredEvent(entry, type, adapter.subject()), raw);
  }

  @Test
  void upsertMatchesEventMapperForComplete() {
    var change = new SourceChange("IT_SYSTEM", "EAM-1", 184L, ChangeOperation.UPSERT, Completeness.COMPLETE, T, payload());
    assertThat(viaReader(change, "poll:IT_SYSTEM/EAM-1/v184", "corr", CanonicalEvent.TYPE_ASSET_UPSERTED, EventMapper.SCHEMA_UPSERTED))
        .isEqualTo(viaAdapter(change, "poll:IT_SYSTEM/EAM-1/v184", "corr"));
  }

  @Test
  void upsertMatchesEventMapperForPartialWithoutCorrelation() {
    var change = new SourceChange("SERVICE", "s/1", 99L, ChangeOperation.UPSERT, Completeness.PARTIAL, T, Map.of("name", "x"));
    assertThat(viaReader(change, "e1", null, CanonicalEvent.TYPE_ASSET_UPSERTED, EventMapper.SCHEMA_UPSERTED))
        .isEqualTo(viaAdapter(change, "e1", null));
  }

  @Test
  void deleteMatchesEventMapperAndHasEmptyPayload() {
    var change = new SourceChange("IT_SYSTEM", "EAM-1", 185L, ChangeOperation.DELETE, Completeness.COMPLETE, T, Map.of());
    var event = viaReader(change, "e2", "corr", EventMapper.TYPE_ASSET_DELETED, EventMapper.SCHEMA_DELETED);
    assertThat(event).isEqualTo(viaAdapter(change, "e2", "corr"));
    assertThat(event.data().payload()).isEmpty();
  }

  @Test
  void brokenRawIsRejectedWithoutQuotingContent() {
    var change = new SourceChange("IT_SYSTEM", "EAM-1", 1L, ChangeOperation.UPSERT, Completeness.COMPLETE, T, Map.of());
    var adapter = viaAdapter(change, "e3", null);
    var entry = new JournalEntry(adapter.source(), "e3", null, null, "IT_SYSTEM", "EAM-1", new SourceVersion("1"),
        EventMapper.SCHEMA_UPSERTED, ProcessingStatus.RECEIVED, null, null, 0, new RawPayloadRef("raw/x", "h"), T, T);
    var stored = new StoredEvent(entry, CanonicalEvent.TYPE_ASSET_UPSERTED, adapter.subject());

    assertThatThrownBy(() -> reader.toEvent(stored, "{SECRET-not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
    assertThatThrownBy(() -> reader.toEvent(stored, "{\"completeness\":\"COMPLETE\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
