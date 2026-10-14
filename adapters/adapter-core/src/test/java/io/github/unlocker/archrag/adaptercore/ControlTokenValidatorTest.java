package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Проверка токена на {@code /control/snapshot}: нет токена, чужой issuer/ключ/audience, просрочен, нет роли, ok. */
class ControlTokenValidatorTest {

  private static final String ISSUER = "http://idp.test/realms/archrag";
  private static final String AUDIENCE = "urn:archrag:admin";
  private static final String ROLE = "archrag-console-admin";

  private static final RSAKey KEY = generate("k1");
  private static final RSAKey FOREIGN_KEY = generate("k1");

  private final ControlTokenValidator validator =
      new ControlTokenValidator(ISSUER, new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())), AUDIENCE, ROLE);

  private static RSAKey generate(String id) {
    try {
      return new RSAKeyGenerator(2048).keyID(id).generate();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String token(RSAKey signer, String issuer, String audience, Instant exp, List<String> roles) {
    try {
      var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("alice").audience(audience)
          .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(exp))
          .claim("realm_access", Map.of("roles", roles)).build();
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(signer));
      return "Bearer " + jwt.serialize();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String valid() {
    return token(KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), List.of(ROLE, "other"));
  }

  @Test
  void validTokenWithRoleIsOk() {
    assertThat(validator.validate(valid())).isEqualTo(ControlTokenValidator.Result.OK);
  }

  @Test
  void missingOrMalformedHeaderIsUnauthenticated() {
    assertThat(validator.validate(null)).isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate("")).isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate("Basic abc")).isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate("Bearer not-a-jwt")).isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
  }

  @Test
  void foreignIssuerAudienceSignatureAndExpiredAreUnauthenticated() {
    Instant ok = Instant.now().plusSeconds(300);
    assertThat(validator.validate(token(KEY, "http://evil/realms/x", AUDIENCE, ok, List.of(ROLE))))
        .isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate(token(KEY, ISSUER, "urn:other", ok, List.of(ROLE))))
        .isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate(token(FOREIGN_KEY, ISSUER, AUDIENCE, ok, List.of(ROLE))))
        .isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
    assertThat(validator.validate(token(KEY, ISSUER, AUDIENCE, Instant.now().minusSeconds(3600), List.of(ROLE))))
        .isEqualTo(ControlTokenValidator.Result.UNAUTHENTICATED);
  }

  @Test
  void validTokenWithoutRoleIsForbidden() {
    Instant ok = Instant.now().plusSeconds(300);
    assertThat(validator.validate(token(KEY, ISSUER, AUDIENCE, ok, List.of("archrag-reader"))))
        .isEqualTo(ControlTokenValidator.Result.FORBIDDEN);
    assertThat(validator.validate(token(KEY, ISSUER, AUDIENCE, ok, List.of())))
        .isEqualTo(ControlTokenValidator.Result.FORBIDDEN);
  }

  @Test
  void incompleteConfigurationIsRejected() {
    var keys = new ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet(KEY.toPublicJWK()));
    assertThatThrownBy(() -> new ControlTokenValidator(" ", keys, AUDIENCE, ROLE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ControlTokenValidator(ISSUER, keys, null, ROLE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
