package io.github.unlocker.archrag.adaptercore;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Проверяет bearer-токен администратора на {@code POST /control/snapshot}: подпись по JWKS (только RS256), issuer,
 * срок, audience и realm-роль из {@code realm_access.roles}. Токен недействителен (нет, не JWT, чужая подпись или
 * issuer, просрочен, не тот audience) — {@link Result#UNAUTHENTICATED}; токен действителен, но роли нет —
 * {@link Result#FORBIDDEN}. Токен и причина отказа в лог и исключения не попадают.
 */
public final class ControlTokenValidator {

  /** Итог проверки заголовка {@code Authorization}. */
  public enum Result {
    /** Токен валиден и содержит нужную роль. */
    OK,
    /** Токена нет или он недействителен: ответ {@code 401}. */
    UNAUTHENTICATED,
    /** Токен действителен, но без нужной роли: ответ {@code 403}. */
    FORBIDDEN
  }

  private static final String BEARER = "Bearer ";

  private final String role;
  private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

  /**
   * @param issuer ожидаемый {@code iss}
   * @param jwksUri адрес JWKS издателя (с кэшем и повторной выборкой при смене ключа)
   * @param audience обязательный {@code aud}
   * @param role realm-роль, которую обязан иметь токен
   */
  public ControlTokenValidator(String issuer, URI jwksUri, String audience, String role) {
    this(issuer, remoteKeys(jwksUri), audience, role);
  }

  ControlTokenValidator(String issuer, JWKSource<SecurityContext> keys, String audience, String role) {
    Objects.requireNonNull(keys, "keys");
    if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank() || role == null || role.isBlank()) {
      throw new IllegalArgumentException("issuer, audience and role are required");
    }
    this.role = role;
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        Set.of(audience), new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp", "iss", "aud"), Set.of()));
  }

  /** @param authorization значение заголовка {@code Authorization} или {@code null} */
  public Result validate(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Result.UNAUTHENTICATED;
    }
    JWTClaimsSet claims;
    try {
      claims = processor.process(authorization.substring(BEARER.length()).strip(), null);
    } catch (Exception e) {
      // Причина (подпись, срок, недоступность JWKS) наружу не раскрывается; клиент видит только 401.
      return Result.UNAUTHENTICATED;
    }
    return hasRole(claims) ? Result.OK : Result.FORBIDDEN;
  }

  private boolean hasRole(JWTClaimsSet claims) {
    Object realm = claims.getClaim("realm_access");
    return realm instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles && roles.contains(role);
  }

  private static JWKSource<SecurityContext> remoteKeys(URI jwksUri) {
    try {
      return JWKSourceBuilder.create(jwksUri.toURL()).build();
    } catch (MalformedURLException | IllegalArgumentException e) {
      throw new IllegalArgumentException("jwksUri is invalid");
    }
  }
}
