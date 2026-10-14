package io.github.unlocker.archrag.ingestionservice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки жизненного цикла {@code SyncRun} и фоновой чистки ({@link HousekeepingJob}).
 *
 * @param staleAfter возраст {@code RUNNING}-прогона (и записи {@code admin_audit} в {@code STARTED}), после которого
 *     адаптер считается умершим и прогон переводится в {@code FAILED}
 * @param keepPerSource сколько терминальных прогонов на адаптер хранится; более старые удаляются
 * @param housekeepingInterval пауза между проходами чистки
 * @param enabled {@code false} отключает фоновую чистку (детерминированные IT)
 */
@ConfigurationProperties("archrag.sync-run")
public record SyncRunProperties(
    @DefaultValue("6h") Duration staleAfter,
    @DefaultValue("20") int keepPerSource,
    @DefaultValue("1h") Duration housekeepingInterval,
    @DefaultValue("true") boolean enabled) {

  public SyncRunProperties {
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("archrag.sync-run.stale-after must be positive");
    }
    if (keepPerSource < 1) {
      throw new IllegalArgumentException("archrag.sync-run.keep-per-source must be at least 1");
    }
    if (housekeepingInterval.isZero() || housekeepingInterval.isNegative()) {
      throw new IllegalArgumentException("archrag.sync-run.housekeeping-interval must be positive");
    }
  }
}
