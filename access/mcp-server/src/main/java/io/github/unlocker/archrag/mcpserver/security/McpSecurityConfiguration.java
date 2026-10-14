package io.github.unlocker.archrag.mcpserver.security;

import io.github.unlocker.archrag.mcpserver.audit.ToolCallAuditor;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * MCP server как OAuth2 resource server: JWT (issuer + audience) на каждый запрос, Protected
 * Resource Metadata (RFC 9728), realm-роль {@value #READER_ROLE} на любой запрос и проверка scope на
 * {@code tools/call}.
 */
@Configuration
@EnableConfigurationProperties(McpSecurityProperties.class)
public class McpSecurityConfiguration {

  /** Realm-роль Keycloak читателя: нужна для всех JSON-RPC методов {@code /mcp}, включая {@code initialize}. */
  static final String READER_ROLE = "archrag-reader";

  /** Цепочка безопасности: без сессий и CSRF, всё кроме health и метаданных требует токен. */
  @Bean
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http,
      McpSecurityProperties properties,
      ToolCallAuditor auditor,
      OAuth2ResourceServerProperties resourceServer)
      throws Exception {
    String issuer = resourceServer.getJwt().getIssuerUri();
    List<String> scopes = properties.toolScopes().values().stream().distinct().sorted().toList();
    // Фильтр создаётся через new, а не бином: иначе Boot зарегистрирует его ещё и как
    // servlet-фильтр.
    var toolScopeFilter = new ToolScopeFilter(properties, auditor);
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health/**", "/.well-known/oauth-protected-resource/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole(READER_ROLE))
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .protectedResourceMetadata(
                        m ->
                            m.protectedResourceMetadataCustomizer(
                                b ->
                                    b.resource(properties.resourceUri())
                                        .authorizationServer(issuer)
                                        .scopes(c -> c.addAll(scopes))
                                        // bearer_methods_supported = ["header"] выставляется по
                                        // умолчанию
                                        .resourceName("arch-rag"))))
        .addFilterAfter(toolScopeFilter, AuthorizationFilter.class);
    return http.build();
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
