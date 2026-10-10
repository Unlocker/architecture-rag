package io.github.unlocker.archrag.adminconsole.security;

import io.github.unlocker.archrag.adminconsole.config.ConsoleProperties;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Консоль как OAuth2 resource server: JWT (issuer + обязательный audience) и scope
 * {@code architecture.admin} на {@code /api/**}; статика, {@code console-config.json} и health публичны,
 * всё остальное закрыто (fail-closed).
 */
@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleSecurityConfiguration {

  static final String SCOPE_AUTHORITY = "SCOPE_" + ConsoleProperties.ADMIN_SCOPE;

  /** Цепочка безопасности: без сессий и CSRF (токен в заголовке), CSP и nosniff на всех ответах. */
  @Bean
  SecurityFilterChain consoleSecurityFilterChain(
      HttpSecurity http, OAuth2ResourceServerProperties resourceServer) throws Exception {
    String csp = contentSecurityPolicy(resourceServer.getJwt().getIssuerUri());
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            h ->
                h.contentTypeOptions(Customizer.withDefaults())
                    .contentSecurityPolicy(c -> c.policyDirectives(csp)))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/console-config.json")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .hasAuthority(SCOPE_AUTHORITY)
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    // Статика и маршруты SPA: только чтение.
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /**
   * CSP консоли. {@code connect-src} содержит origin issuer, а не его полный URI: источник с путём
   * совпадает только с точным путём и блокировал бы token endpoint.
   */
  static String contentSecurityPolicy(String issuerUri) {
    URI issuer = URI.create(issuerUri);
    String origin = issuer.getScheme() + "://" + issuer.getAuthority();
    return "default-src 'self'; connect-src 'self' " + origin
        + "; frame-ancestors 'none'; base-uri 'self'; object-src 'none'";
  }
}
