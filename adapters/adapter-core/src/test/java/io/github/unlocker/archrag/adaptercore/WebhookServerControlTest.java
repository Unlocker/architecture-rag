package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /control/snapshot}: 202 / 409 / 503, чужие методы, отсутствие контекста, 401 без токена и 403 без роли —
 * без запуска snapshot.
 */
class WebhookServerControlTest {

  private static final String ISSUER = "http://idp.test/realms/archrag";
  private static final String AUDIENCE = "urn:archrag:admin";
  private static final String ROLE = "archrag-console-admin";
  private static final RSAKey KEY = key();
  private static final ControlTokenValidator AUTH =
      new ControlTokenValidator(ISSUER, new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK())), AUDIENCE, ROLE);

  private static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID("k").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String bearer(String role) throws Exception {
    var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("alice")
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .claim("realm_access", Map.of("roles", List.of(role))).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k").build(), claims);
    jwt.sign(new RSASSASigner(KEY));
    return "Bearer " + jwt.serialize();
  }

  private static WebhookServer server(Supplier<PollResult> snapshot) {
    return server(snapshot, AUTH);
  }

  private static WebhookServer server(Supplier<PollResult> snapshot, ControlTokenValidator auth) {
    var config = AdapterConfig.of(SourceSystem.EAM, URI.create("http://unused"), "secret-value");
    var handler = new WebhookHandler(config, StubSources.eam(Clock.systemUTC()), new InMemoryJournal(),
        new InMemoryRawStore(), Clock.systemUTC());
    return new WebhookServer(handler, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), snapshot, auth);
  }

  private static HttpResponse<String> call(WebhookServer s, String method) throws Exception {
    return call(s, method, bearer(ROLE));
  }

  private static HttpResponse<String> call(WebhookServer s, String method, String authorization) throws Exception {
    URI u = s.endpoint().resolve("/control/snapshot");
    var request = HttpRequest.newBuilder(u).method(method, HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void completedSnapshotIs202WithOutcome() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      return new PollResult(PollResult.Outcome.SNAPSHOT_COMPLETED, 5, "run-1");
    })) {
      var r = call(s, "POST");
      assertThat(r.statusCode()).isEqualTo(202);
      assertThat(r.body()).contains("SNAPSHOT_COMPLETED").contains("run-1");
      assertThat(calls[0]).isEqualTo(1);
    }
  }

  @Test
  void alreadyRunningIs409AndUnavailableSourceIs503() throws Exception {
    try (var s = server(() -> new PollResult(PollResult.Outcome.ALREADY_RUNNING, 0, null))) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(409);
    }
    try (var s = server(() -> new PollResult(PollResult.Outcome.GAVE_UP, 0, "run-2"))) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(503);
    }
  }

  @Test
  void failureIs500WithoutDetailsAndGetIsRejectedWithoutRunning() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      throw new IllegalStateException("secret detail");
    })) {
      var r = call(s, "POST");
      assertThat(r.statusCode()).isEqualTo(500);
      assertThat(r.body()).doesNotContain("secret detail");
      assertThat(call(s, "GET").statusCode()).isEqualTo(405);
      assertThat(calls[0]).isEqualTo(1);
    }
  }

  @Test
  void noControlContextWithoutSnapshotSupplier() throws Exception {
    try (var s = server(null)) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void withoutTokenIs401AndWithoutRoleIs403AndSnapshotIsNotStarted() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      return new PollResult(PollResult.Outcome.SNAPSHOT_COMPLETED, 1, "run-1");
    })) {
      var unauthenticated = call(s, "POST", null);
      assertThat(unauthenticated.statusCode()).isEqualTo(401);
      assertThat(unauthenticated.headers().firstValue("WWW-Authenticate")).contains("Bearer");
      assertThat(call(s, "POST", "Bearer garbage").statusCode()).isEqualTo(401);
      assertThat(call(s, "POST", bearer("archrag-reader")).statusCode()).isEqualTo(403);
      assertThat(calls[0]).isZero();
    }
  }

  @Test
  void noControlContextWithoutTokenValidatorEvenWithToken() throws Exception {
    int[] calls = {0};
    try (var s = server(() -> {
      calls[0]++;
      return new PollResult(PollResult.Outcome.SNAPSHOT_COMPLETED, 1, "run-1");
    }, null)) {
      assertThat(call(s, "POST").statusCode()).isEqualTo(404);
      assertThat(calls[0]).isZero();
    }
  }
}
