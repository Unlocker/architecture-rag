package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.graphprojector.Reconciler;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки reconciliation.
 *
 * @param maxMissingShare доля активных записей источника, которую missing set может занять, {@code (0, 1]};
 *     больше — reconcile не выполняется, маркер {@code snapshot-complete} уходит в карантин
 */
@ConfigurationProperties("archrag.reconcile")
public record ReconcileProperties(
    @DefaultValue("" + Reconciler.DEFAULT_MAX_MISSING_SHARE) double maxMissingShare) {

  public ReconcileProperties {
    if (!(maxMissingShare > 0 && maxMissingShare <= 1)) {
      throw new IllegalArgumentException("archrag.reconcile.max-missing-share must be in (0, 1]");
    }
  }
}
