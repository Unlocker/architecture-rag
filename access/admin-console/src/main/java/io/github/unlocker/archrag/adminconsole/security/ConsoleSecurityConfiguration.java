package io.github.unlocker.archrag.adminconsole.security;

import io.github.unlocker.archrag.adminconsole.config.ConsoleProperties;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Консоль как OAuth2 resource server: JWT (issuer + обязательный audience) и scope
 * {@code architecture.admin} и realm-роль {@value #ADMIN_ROLE} на {@code /api/**}; статика, {@code console-config.json} и health публичны,
 * всё остальное закрыто (fail-closed).
 */
@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleSecurityConfiguration {

  static final String SCOPE_AUTHORITY = "SCOPE_" + ConsoleProperties.ADMIN_SCOPE;

  /** Realm-роль Keycloak администратора; проверяется на {@code /api/**} в дополнение к scope. */
  static final String ADMIN_ROLE = "archrag-console-admin";

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
                    .access(
                        AuthorizationManagers.allOf(
                            AuthorityAuthorizationManager.hasAuthority(SCOPE_AUTHORITY),
                            AuthorityAuthorizationManager.hasRole(ADMIN_ROLE)))
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    // Статика и маршруты SPA: только чтение.
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
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

  /** Scope'ы токена дают {@code SCOPE_...}, realm-роли из {@code realm_access.roles} — {@code ROLE_...}. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
          if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm
              && realm.get("roles") instanceof Collection<?> roles) {
            roles.stream()
                .filter(String.class::isInstance)
                .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
          }
          return authorities;
        });
    return converter;
  }
}
