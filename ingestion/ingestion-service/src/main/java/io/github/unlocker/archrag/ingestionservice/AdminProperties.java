package io.github.unlocker.archrag.ingestionservice;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки админского REST.
 *
 * @param allowedClients клиенты OIDC ({@code azp}), токены которых принимает админский REST; пустой список запрещён
 * @param auditRetention сколько хранятся записи {@code admin_audit}; старше удаляет {@link HousekeepingJob}
 */
@ConfigurationProperties("archrag.admin")
public record AdminProperties(
    @DefaultValue("archrag-demo") List<String> allowedClients, @DefaultValue("90d") Duration auditRetention) {

  public AdminProperties {
    if (allowedClients.isEmpty()) {
      throw new IllegalArgumentException("archrag.admin.allowed-clients must not be empty");
    }
    if (auditRetention.isZero() || auditRetention.isNegative()) {
      throw new IllegalArgumentException("archrag.admin.audit-retention must be positive");
    }
    allowedClients = List.copyOf(allowedClients);
  }
}
