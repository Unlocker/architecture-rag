package io.github.unlocker.archrag.sourcespi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.WebhookSignature.Result;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(5);
  private static final String BODY = "{\"eventId\":\"e1\"}";

  private static String ts(Instant t) {
    return Long.toString(t.getEpochSecond());
  }

  @Test
  void validSignatureIsAccepted() {
    String sig = WebhookSignature.sign("s3cret", NOW.getEpochSecond(), BODY);
    assertThat(WebhookSignature.verify("s3cret", ts(NOW), sig, BODY, NOW, WINDOW))
        .isEqualTo(Result.VALID);
  }

  @Test
  void wrongSecretOrTamperedBodyIsRejected() {
    String sig = WebhookSignature.sign("other", NOW.getEpochSecond(), BODY);
    assertThat(WebhookSignature.verify("s3cret", ts(NOW), sig, BODY, NOW, WINDOW))
        .isEqualTo(Result.BAD_SIGNATURE);
    String good = WebhookSignature.sign("s3cret", NOW.getEpochSecond(), BODY);
    assertThat(WebhookSignature.verify("s3cret", ts(NOW), good, BODY + " ", NOW, WINDOW))
        .isEqualTo(Result.BAD_SIGNATURE);
  }

  @Test
  void timestampOutsideReplayWindowIsRejectedEvenWithValidSignature() {
    Instant old = NOW.minus(Duration.ofMinutes(10));
    String sig = WebhookSignature.sign("s3cret", old.getEpochSecond(), BODY);
    assertThat(WebhookSignature.verify("s3cret", ts(old), sig, BODY, NOW, WINDOW))
        .isEqualTo(Result.OUT_OF_WINDOW);
    Instant future = NOW.plus(Duration.ofMinutes(10));
    String futureSig = WebhookSignature.sign("s3cret", future.getEpochSecond(), BODY);
    assertThat(WebhookSignature.verify("s3cret", ts(future), futureSig, BODY, NOW, WINDOW))
        .isEqualTo(Result.OUT_OF_WINDOW);
  }

  @Test
  void missingOrMalformedHeadersAreRejected() {
    assertThat(WebhookSignature.verify("s", null, "sha256=00", BODY, NOW, WINDOW))
        .isEqualTo(Result.MALFORMED);
    assertThat(WebhookSignature.verify("s", ts(NOW), null, BODY, NOW, WINDOW))
        .isEqualTo(Result.MALFORMED);
    assertThat(WebhookSignature.verify("s", "abc", "sha256=00", BODY, NOW, WINDOW))
        .isEqualTo(Result.MALFORMED);
    assertThat(WebhookSignature.verify("s", ts(NOW), "sha256=zz", BODY, NOW, WINDOW))
        .isEqualTo(Result.MALFORMED);
    assertThat(WebhookSignature.verify("s", ts(NOW), "md5=00", BODY, NOW, WINDOW))
        .isEqualTo(Result.MALFORMED);
  }
}
