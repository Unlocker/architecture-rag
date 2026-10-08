package io.github.unlocker.archrag.ingestionservice;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки админского REST.
 *
 * @param allowedClients клиенты OIDC ({@code azp}), токены которых принимает админский REST; пустой список запрещён
 */
@ConfigurationProperties("archrag.admin")
public record AdminProperties(@DefaultValue("archrag-demo") List<String> allowedClients) {

  public AdminProperties {
    if (allowedClients.isEmpty()) {
      throw new IllegalArgumentException("archrag.admin.allowed-clients must not be empty");
    }
    allowedClients = List.copyOf(allowedClients);
  }
}
