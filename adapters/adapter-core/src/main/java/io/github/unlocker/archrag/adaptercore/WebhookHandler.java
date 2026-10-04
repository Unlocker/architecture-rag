package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcespi.WebhookSignature;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Логика приёма webhook без привязки к HTTP-серверу: подпись → разбор → {@code fetchById} → raw →
 * inbox → ответ.
 *
 * <p>Статусы: {@code 202} — событие зафиксировано в inbox (в том числе повтор, он даёт {@code
 * DUPLICATE} и не создаёт вторую строку); {@code 401} — подпись отсутствует, неверна или вне
 * replay window (без деталей); {@code 400} — тело не разбирается, не совпали идентификатор
 * события в заголовке и теле либо источник; {@code 404} — источник не знает объект при уведомлении об upsert, фиксировать
 * нечего (при уведомлении об удалении фиксируется tombstone по подписанному телу); {@code 503} — источник недоступен, доставку нужно повторить. Сбои журнала и S3
 * пробрасываются: {@code 202} до записи в inbox не отдаётся.
 *
 * <p>Дедупликация идёт по {@code eventId} из подписанного тела: заголовок {@code
 * X-Archrag-Event-Id} подписью не защищён и только сверяется с ним.
 */
public final class WebhookHandler {

  private final AdapterConfig config;
  private final SourceConnector connector;
  private final InboxWriter inbox;
  private final Clock clock;

  public WebhookHandler(
      AdapterConfig config,
      SourceConnector connector,
      EventJournal journal,
      RawPayloadStore rawStore,
      Clock clock) {
    this.config = config;
    this.connector = connector;
    this.inbox = new InboxWriter(config.system(), journal, rawStore);
    this.clock = clock;
  }

  /**
   * Обрабатывает доставку.
   *
   * @param header поиск значения заголовка по имени без учёта регистра, {@code null} если нет
   * @param body тело запроса как есть (подпись считается по этим символам)
   * @return HTTP-статус ответа
   */
  public int handle(Function<String, String> header, String body) {
    WebhookSignature.Result verdict =
        WebhookSignature.verify(
            config.webhookSecret(),
            header.apply(WebhookSignature.TIMESTAMP_HEADER),
            header.apply(WebhookSignature.SIGNATURE_HEADER),
            body,
            clock.instant(),
            config.replayWindow());
    if (verdict != WebhookSignature.Result.VALID) {
      return 401;
    }
    Map<String, Object> notification;
    try {
      notification = Json.parseObject(body);
    } catch (IllegalArgumentException e) {
      return 400;
    }
    String eventId = text(notification, "eventId");
    String sourceType = text(notification, "sourceType");
    String sourceId = text(notification, "sourceId");
    if (eventId == null
        || sourceType == null
        || sourceId == null
        || !config.system().code().equals(notification.get("source"))
        || !eventId.equals(header.apply(WebhookSignature.EVENT_ID_HEADER))) {
      return 400;
    }
    Optional<SourceChange> state;
    try {
      state = connector.fetchById(sourceType, sourceId);
    } catch (SourceUnavailableException e) {
      return 503;
    }
    SourceChange change;
    if (state.isPresent()) {
      change = state.get();
    } else if ("DELETE".equals(notification.get("operation"))) {
      // Источник уже забыл удалённый объект: tombstone строим по подписанному уведомлению.
      Long version = notification.get("sourceVersion") instanceof Number n ? n.longValue() : null;
      if (version == null || version < 0) {
        return 400;
      }
      change = new SourceChange(sourceType, sourceId, version, ChangeOperation.DELETE,
          Completeness.COMPLETE, clock.instant(), Map.of());
    } else {
      return 404;
    }
    // append вернул управление: событие в inbox (RECEIVED или DUPLICATE).
    inbox.record(eventId, change, eventId, null);
    return 202;
  }

  private static String text(Map<String, Object> m, String key) {
    return m.get(key) instanceof String s && !s.isBlank() ? s : null;
  }
}
