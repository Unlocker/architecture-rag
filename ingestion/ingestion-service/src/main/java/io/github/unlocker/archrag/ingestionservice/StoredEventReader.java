package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Собирает {@link CanonicalEvent} из строки журнала и raw payload. Метаданные берутся из строки inbox,
 * {@code payload}, {@code operation}, {@code completeness} и {@code updatedAt} (→ {@code time}) — из raw.
 *
 * <p>Правила те же, что у {@code EventMapper.toEvent} адаптеров: для {@code UPSERT} поля {@code null} отбрасываются,
 * {@code _completeness} кладётся в payload; для {@code DELETE} payload пустой. Целые числа читаются как
 * {@code Long}, как в адаптерах. Raw недоверенный: ошибки разбора — {@link IllegalArgumentException} без его
 * содержимого.
 */
@Component
public class StoredEventReader {

  static final String COMPLETENESS_KEY = "_completeness";
  private static final String OPERATION_DELETE = "DELETE";

  private final JsonMapper json;

  public StoredEventReader(JsonMapper json) {
    this.json = json.rebuild().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
  }

  /** Событие по строке журнала и байтам её raw payload; {@code id} остаётся исходным. */
  public CanonicalEvent toEvent(StoredEvent stored, byte[] raw) {
    JournalEntry entry = stored.entry();
    JsonNode root;
    try {
      root = json.readTree(new String(raw, StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      throw new IllegalArgumentException("raw payload is not valid JSON");
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("raw payload is not a JSON object");
    }
    boolean delete = OPERATION_DELETE.equals(text(root, "operation"));
    Instant time = time(root);
    Map<String, Object> payload = new LinkedHashMap<>();
    if (!delete) {
      JsonNode node = root.get("payload");
      if (node != null && node.isObject()) {
        Map<String, Object> fields = json.convertValue(node, new tools.jackson.core.type.TypeReference<>() {});
        fields.forEach((k, v) -> {
          if (v != null) {
            payload.put(k, v);
          }
        });
      }
      String completeness = text(root, "completeness");
      if (completeness == null) {
        throw new IllegalArgumentException("raw payload has no completeness");
      }
      payload.put(COMPLETENESS_KEY, completeness);
    }
    return new CanonicalEvent(
        entry.eventId(),
        entry.source(),
        stored.type(),
        stored.subject(),
        time,
        entry.schemaVersion(),
        entry.correlationId(),
        new AssetEventData(entry.sourceType(), entry.sourceId(), new SourceVersion(entry.sourceVersion().value()), payload));
  }

  private static Instant time(JsonNode root) {
    String text = text(root, "updatedAt");
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new IllegalArgumentException("raw payload has no valid updatedAt");
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return n == null || n.isNull() ? null : n.asString();
  }
}
