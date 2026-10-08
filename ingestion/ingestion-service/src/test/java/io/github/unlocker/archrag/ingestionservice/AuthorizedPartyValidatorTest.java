package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Проверка {@code azp}: свой клиент проходит; чужой, пустой и отсутствующий claim отклоняются. */
class AuthorizedPartyValidatorTest {

  private final AuthorizedPartyValidator validator = new AuthorizedPartyValidator(Set.of("archrag-demo"));

  private static Jwt jwt(String azp) {
    return Jwt.withTokenValue("t").header("alg", "none").subject("s").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60)).claims(c -> {
          if (azp != null) {
            c.put("azp", azp);
          }
        }).build();
  }

  @Test
  void allowedClientPasses() {
    assertThat(validator.validate(jwt("archrag-demo")).hasErrors()).isFalse();
  }

  @Test
  void foreignClientIsRejected() {
    assertThat(validator.validate(jwt("archrag-admin-console")).hasErrors()).isTrue();
  }

  @Test
  void missingOrEmptyClaimIsRejected() {
    assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    assertThat(validator.validate(jwt("")).hasErrors()).isTrue();
  }

  @Test
  void emptyAllowListIsRejectedByProperties() {
    assertThatThrownBy(() -> new AdminProperties(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThat(new AdminProperties(List.of("a")).allowedClients()).containsExactly("a");
  }
}
