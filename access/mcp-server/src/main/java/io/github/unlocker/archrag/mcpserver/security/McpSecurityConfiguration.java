package io.github.unlocker.archrag.mcpserver.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * MCP server как OAuth2 resource server: JWT (issuer + audience) на каждый запрос, Protected
 * Resource Metadata (RFC 9728) и проверка scope на {@code tools/call}.
 */
@Configuration
@EnableConfigurationProperties(McpSecurityProperties.class)
public class McpSecurityConfiguration {

  /** Цепочка безопасности: без сессий и CSRF, всё кроме health и метаданных требует токен. */
  @Bean
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http,
      McpSecurityProperties properties,
      OAuth2ResourceServerProperties resourceServer)
      throws Exception {
    String issuer = resourceServer.getJwt().getIssuerUri();
    List<String> scopes = properties.toolScopes().values().stream().distinct().sorted().toList();
    // Фильтр создаётся через new, а не бином: иначе Boot зарегистрирует его ещё и как servlet-фильтр.
    var toolScopeFilter = new ToolScopeFilter(properties);
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health/**", "/.well-known/oauth-protected-resource/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(Customizer.withDefaults())
                    .protectedResourceMetadata(
                        m ->
                            m.protectedResourceMetadataCustomizer(
                                b ->
                                    b.resource(properties.resourceUri())
                                        .authorizationServer(issuer)
                                        .scopes(c -> c.addAll(scopes))
                                        // bearer_methods_supported = ["header"] выставляется по умолчанию
                                        .resourceName("arch-rag"))))
        .addFilterAfter(toolScopeFilter, AuthorizationFilter.class);
    return http.build();
  }
}
