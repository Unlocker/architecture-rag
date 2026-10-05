package io.github.unlocker.archrag.sourcespi;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись webhook — контракт между источником и адаптером.
 *
 * <p>Заголовки: {@code X-Archrag-Signature: sha256=<hex>}, где {@code <hex>} — {@code
 * HMAC-SHA256(secret, timestamp + "." + body)}; {@code X-Archrag-Timestamp} — unix-секунды
 * отправки; {@code X-Archrag-Event-Id} — идентификатор события.
 *
 * <p>Проверка сравнивает подпись за постоянное время и отвергает запросы вне replay window.
 * Секрет не попадает в исключения и сообщения.
 */
public final class WebhookSignature {

  /** Заголовок с подписью. */
  public static final String SIGNATURE_HEADER = "X-Archrag-Signature";

  /** Заголовок с временем отправки в epoch seconds. */
  public static final String TIMESTAMP_HEADER = "X-Archrag-Timestamp";

  /** Заголовок с идентификатором события. */
  public static final String EVENT_ID_HEADER = "X-Archrag-Event-Id";

  private static final String PREFIX = "sha256=";

  /** Результат проверки. */
  public enum Result {
    VALID,
    MALFORMED,
    BAD_SIGNATURE,
    /** Timestamp вне replay window (слишком старый или из будущего). */
    OUT_OF_WINDOW
  }

  private WebhookSignature() {}

  /** Вычисляет значение заголовка подписи. */
  public static String sign(String secret, long timestampSeconds, String body) {
    return PREFIX + HexFormat.of().formatHex(hmac(secret, timestampSeconds + "." + body));
  }

  /**
   * Проверяет подпись и timestamp.
   *
   * @param timestampHeader значение {@link #TIMESTAMP_HEADER}, может быть {@code null}
   * @param signatureHeader значение {@link #SIGNATURE_HEADER}, может быть {@code null}
   * @param window допустимое отклонение timestamp от {@code now} в обе стороны
   */
  public static Result verify(
      String secret,
      String timestampHeader,
      String signatureHeader,
      String body,
      Instant now,
      Duration window) {
    if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
      return Result.MALFORMED;
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(timestampHeader);
    } catch (NumberFormatException e) {
      return Result.MALFORMED;
    }
    byte[] provided;
    try {
      provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return Result.MALFORMED;
    }
    byte[] expected = hmac(secret, timestamp + "." + body);
    if (!MessageDigest.isEqual(expected, provided)) {
      return Result.BAD_SIGNATURE;
    }
    long skew = Math.abs(now.getEpochSecond() - timestamp);
    return skew > window.toSeconds() ? Result.OUT_OF_WINDOW : Result.VALID;
  }

  private static byte[] hmac(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
