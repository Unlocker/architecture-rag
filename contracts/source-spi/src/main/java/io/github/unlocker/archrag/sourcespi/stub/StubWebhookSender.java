package io.github.unlocker.archrag.sourcespi.stub;

import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcespi.WebhookSignature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Отправляет подписанный webhook на endpoint адаптера. Подпись и заголовки — по {@link
 * WebhookSignature}. Кроме корректной отправки умеет формировать негативные случаи: устаревший
 * timestamp и неверную подпись.
 */
public final class StubWebhookSender implements AutoCloseable {

  private final URI target;
  private final String secret;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * @param target URL webhook-endpoint'а адаптера
   * @param secret общий секрет подписи; в логи и исключения не попадает
   */
  public StubWebhookSender(URI target, String secret, Clock clock) {
    this.target = target;
    this.secret = secret;
    this.clock = clock;
  }

  /** Отправляет событие с корректной подписью; возвращает HTTP-статус ответа. */
  public int send(WebhookEvent event) {
    return sendAt(event, clock.instant());
  }

  /** Отправляет корректно подписанное событие с заданным timestamp (проверка replay window). */
  public int sendAt(WebhookEvent event, Instant timestamp) {
    String body = body(event);
    return post(event, body, timestamp.getEpochSecond(), WebhookSignature.sign(secret, timestamp.getEpochSecond(), body));
  }

  /** Отправляет событие с подписью, вычисленной другим секретом. */
  public int sendWithBadSignature(WebhookEvent event) {
    String body = body(event);
    long ts = clock.instant().getEpochSecond();
    return post(event, body, ts, WebhookSignature.sign(secret + "-wrong", ts, body));
  }

  /** Тело webhook: только уведомление, без состояния объекта. */
  static String body(WebhookEvent e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("eventId", e.eventId());
    m.put("source", e.source());
    m.put("sourceType", e.sourceType());
    m.put("sourceId", e.sourceId());
    m.put("sourceVersion", e.sourceVersion());
    m.put("operation", e.operation());
    m.put("occurredAt", e.occurredAt());
    return Json.write(m);
  }

  private int post(WebhookEvent event, String body, long timestamp, String signature) {
    HttpRequest request =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header(WebhookSignature.EVENT_ID_HEADER, event.eventId())
            .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
            .header(WebhookSignature.SIGNATURE_HEADER, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      throw new IllegalStateException("webhook delivery failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("webhook delivery interrupted", e);
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
