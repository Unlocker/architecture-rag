package io.github.unlocker.archrag.mcpserver;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Подписанные тестовые JWT: RSA-ключ создаётся один раз на JVM, на диск не пишется; публичный ключ отдаётся локальным JWKS. */
final class TestJwt {

  static final String ISSUER = "https://idp.test/realms/archrag";
  static final String RESOURCE = "https://mcp.test/mcp";

  private static final KeyPair KEYS = generate();
  private static final HttpServer JWKS = startJwksServer();

  private TestJwt() {}

  /** Подключает проверку JWT по локальному JWKS и тестовые issuer/resource. */
  static void register(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    registry.add("archrag.mcp.security.resource-uri", () -> RESOURCE);
  }

  /** Валидный токен с заданными scopes (может быть пустым). */
  static String token(String... scopes) {
    return token(List.of(scopes), RESOURCE, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
  }

  static String token(List<String> scopes, String audience, String issuer, Instant expiresAt) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject("test-user")
              .audience(audience)
              .issueTime(Date.from(Instant.now().minusSeconds(60)))
              .expirationTime(Date.from(expiresAt))
              .claim("scope", String.join(" ", scopes))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair generate() {
    try {
      var gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Локальный JWKS endpoint: issuer-uri в Boot включает discovery, поэтому ключ отдаётся по jwk-set-uri. */
  private static HttpServer startJwksServer() {
    try {
      byte[] body =
          new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("test").build())
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
