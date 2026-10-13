package io.github.unlocker.archrag.ingestionservice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Защита админского эндпоинта: {@code /admin/**} доступен только токену со scope {@value #ADMIN_SCOPE} и
 * realm-ролью {@value #ADMIN_ROLE} ({@code realm_access.roles}, ложится в authority {@code ROLE_...}); всё
 * остальное закрыто. Без токена — 401, без scope или роли — 403. Издателя токенов задаёт
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, ожидаемый audience —
 * {@code spring.security.oauth2.resourceserver.jwt.audiences}. Проверяются издатель, подпись, срок, audience и scope;
 * токен того же издателя, выданный для другого ресурса, отклоняется с 401. Дополнительно claim {@code azp} должен быть
 * в списке {@code archrag.admin.allowed-clients}: audience админки выдаёт общий scope, и без этой проверки токен
 * любого клиента с ним (например, консоли) попал бы в записывающий REST.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class SecurityConfiguration {

  /** OIDC scope админских операций. */
  public static final String ADMIN_SCOPE = "architecture.admin";

  /** Realm-роль Keycloak администратора; проверяется в дополнение к scope. */
  public static final String ADMIN_ROLE = "archrag-console-admin";

  /** Подхватывается автоконфигурацией декодера Spring Boot вместе с проверками issuer и audience. */
  @Bean
  OAuth2TokenValidator<Jwt> authorizedPartyValidator(AdminProperties admin) {
    return new AuthorizedPartyValidator(Set.copyOf(admin.allowedClients()));
  }

  @Bean
  SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/admin/**").access(AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority("SCOPE_" + ADMIN_SCOPE),
                AuthorityAuthorizationManager.hasRole(ADMIN_ROLE)))
            .anyRequest().denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .build();
  }

  /** Scope'ы токена дают {@code SCOPE_...}, realm-роли из {@code realm_access.roles} — {@code ROLE_...}. */
  static JwtAuthenticationConverter jwtAuthenticationConverter() {
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
      if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm && realm.get("roles") instanceof Collection<?> roles) {
        roles.stream().filter(String.class::isInstance)
            .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
      }
      return authorities;
    });
    return converter;
  }
}
