package io.github.unlocker.archrag.ingestionservice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Защита админского эндпоинта: {@code /admin/**} доступен только токену со scope {@value #ADMIN_SCOPE}; всё
 * остальное закрыто. Без токена — 401, без scope — 403. Издателя токенов задаёт
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  /** OIDC scope админских операций. */
  public static final String ADMIN_SCOPE = "architecture.admin";

  @Bean
  SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/admin/**").hasAuthority("SCOPE_" + ADMIN_SCOPE)
            .anyRequest().denyAll())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .build();
  }
}
