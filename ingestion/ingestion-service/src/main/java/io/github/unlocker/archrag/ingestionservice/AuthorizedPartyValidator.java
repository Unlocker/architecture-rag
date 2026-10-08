package io.github.unlocker.archrag.ingestionservice;

import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Принимает токен только от клиента из allow-list по claim {@code azp}. Нужен потому, что audience админки выдаётся
 * client scope {@code architecture.admin}, который запрашивают и другие клиенты (консоль): без проверки {@code azp}
 * их токен получил бы доступ к записывающему REST. Отсутствие claim считается отказом (токен отклоняется с 401).
 */
final class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error FOREIGN_CLIENT =
      new OAuth2Error("invalid_token", "The authorized party is not allowed for this resource", null);

  private final Set<String> allowedClients;

  AuthorizedPartyValidator(Set<String> allowedClients) {
    this.allowedClients = Set.copyOf(allowedClients);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String azp = token.getClaimAsString("azp");
    return azp != null && allowedClients.contains(azp)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(FOREIGN_CLIENT);
  }
}
