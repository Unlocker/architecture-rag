package io.github.unlocker.archrag.adminconsole.config;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Публичная конфигурация для SPA: что нужно для OIDC-логина, секретов здесь нет. */
@RestController
public class ConsoleConfigController {

  /**
   * Конфигурация логина.
   *
   * @param issuer issuer OIDC
   * @param clientId публичный клиент
   * @param scope запрашиваемый scope
   */
  public record ConsoleConfig(String issuer, String clientId, String scope) {}

  private final ConsoleConfig config;

  ConsoleConfigController(ConsoleProperties properties, OAuth2ResourceServerProperties resourceServer) {
    this.config =
        new ConsoleConfig(resourceServer.getJwt().getIssuerUri(), properties.clientId(), properties.scope());
  }

  @GetMapping("/console-config.json")
  public ConsoleConfig config() {
    return config;
  }
}
