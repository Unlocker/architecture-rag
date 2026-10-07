package io.github.unlocker.archrag.adminconsole.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки консоли.
 *
 * @param resourceUri канонический URI ресурса: он же обязательный audience токена; без него сервис не стартует
 * @param clientId публичный клиент Keycloak, под которым UI получает токен
 * @param scope scope, который UI запрашивает при логине
 */
@ConfigurationProperties("archrag.console")
public record ConsoleProperties(
    String resourceUri,
    @DefaultValue("archrag-admin-console") String clientId,
    @DefaultValue("openid architecture.admin") String scope) {

  /** Scope, который требуется для {@code /api/**}. */
  public static final String ADMIN_SCOPE = "architecture.admin";

  public ConsoleProperties {
    if (resourceUri == null || resourceUri.isBlank()) {
      throw new IllegalArgumentException(
          "archrag.console.resource-uri (ARCHRAG_CONSOLE_RESOURCE_URI) must be set: it is the required token audience");
    }
  }
}
