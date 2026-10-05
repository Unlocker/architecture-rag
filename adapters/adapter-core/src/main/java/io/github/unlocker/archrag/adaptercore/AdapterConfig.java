package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Конфигурация адаптера. Секрет webhook берётся из env/secrets; {@link #toString()} его не
 * показывает.
 *
 * @param system источник, который обслуживает адаптер
 * @param sourceBaseUri базовый URL API источника
 * @param webhookSecret общий секрет подписи webhook
 * @param replayWindow допустимое отклонение timestamp webhook, положительное
 * @param pageSize размер страницы polling, положительный
 * @param pollInterval пауза между циклами polling, положительная
 * @param reconcileInterval пауза между полными snapshot для reconciliation, положительная
 * @param retry политика повторов при недоступности источника
 */
public record AdapterConfig(
    SourceSystem system,
    URI sourceBaseUri,
    String webhookSecret,
    Duration replayWindow,
    int pageSize,
    Duration pollInterval,
    Duration reconcileInterval,
    RetryPolicy retry) {

  /** Окно replay по умолчанию. */
  public static final Duration DEFAULT_REPLAY_WINDOW = Duration.ofMinutes(5);

  public AdapterConfig {
    Objects.requireNonNull(system, "system");
    Objects.requireNonNull(sourceBaseUri, "sourceBaseUri");
    Objects.requireNonNull(retry, "retry");
    Objects.requireNonNull(reconcileInterval, "reconcileInterval");
    if (webhookSecret == null || webhookSecret.isBlank()) {
      throw new IllegalArgumentException("webhookSecret is required");
    }
    if (replayWindow.isNegative() || replayWindow.isZero()) {
      throw new IllegalArgumentException("replayWindow must be positive");
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
    if (reconcileInterval.isNegative() || reconcileInterval.isZero()) {
      throw new IllegalArgumentException("reconcileInterval must be positive");
    }
  }

  /** Интервал reconciliation по умолчанию. */
  public static final Duration DEFAULT_RECONCILE_INTERVAL = Duration.ofHours(6);

  /** Конфигурация с параметрами по умолчанию. */
  public static AdapterConfig of(SourceSystem system, URI sourceBaseUri, String webhookSecret) {
    return new AdapterConfig(system, sourceBaseUri, webhookSecret, DEFAULT_REPLAY_WINDOW, 100,
        Duration.ofSeconds(30), DEFAULT_RECONCILE_INTERVAL, RetryPolicy.defaults());
  }

  /** Значение {@code source} в событиях, ключах дедупликации и checkpoint, например {@code urn:corp:eam}. */
  public String sourceUrn() {
    return EventMapper.sourceUrn(system);
  }

  @Override
  public String toString() {
    return "AdapterConfig[system=" + system + ", sourceBaseUri=" + sourceBaseUri
        + ", webhookSecret=***, replayWindow=" + replayWindow + ", pageSize=" + pageSize + "]";
  }
}
