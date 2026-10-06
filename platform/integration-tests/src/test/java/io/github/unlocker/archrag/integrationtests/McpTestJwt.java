package io.github.unlocker.archrag.integrationtests;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
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

/**
 * Подписанные тестовые JWT для MCP-сервера: RSA-ключ в памяти, публичный ключ отдаёт локальный JWKS.
 * Копия {@code TestJwt} из mcp-server (его test-sources недоступны, test-jar не подключаем);
 * issuer и audience совпадают с {@code archrag.mcp.security.resource-uri}.
 */
final class McpTestJwt {

  static final String RESOURCE = "https://mcp.test/mcp";

  private final KeyPair keys;
  private final HttpServer jwks;

  McpTestJwt() {
    try {
      var gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      keys = gen.generateKeyPair();
      byte[] body = new JWKSet(new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID("test").build())
          .toString().getBytes(StandardCharsets.UTF_8);
      jwks = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      jwks.createContext("/jwks", exchange -> {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
          out.write(body);
        }
      });
      jwks.start();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  String jwkSetUri() {
    return "http://localhost:" + jwks.getAddress().getPort() + "/jwks";
  }

  /** Валидный токен с заданными scopes (может быть пустым). */
  String token(String... scopes) {
    try {
      var claims = new JWTClaimsSet.Builder().issuer(RESOURCE).subject("test-user").audience(RESOURCE)
          .issueTime(Date.from(Instant.now().minusSeconds(60)))
          .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
          .claim("scope", String.join(" ", List.of(scopes))).build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  void close() {
    jwks.stop(0);
  }
}
