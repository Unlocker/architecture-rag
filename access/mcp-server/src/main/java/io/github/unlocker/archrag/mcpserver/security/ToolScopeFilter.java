package io.github.unlocker.archrag.mcpserver.security;

import io.github.unlocker.archrag.mcpserver.audit.ToolCallAuditor;
import io.github.unlocker.archrag.mcpserver.audit.ToolDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проверяет scope на {@code tools/call} до диспетчеризации в MCP.
 *
 * <p>Работает только на {@code POST /mcp}. Тело читается не более {@code max-request-bytes} и
 * передаётся дальше буферизованным. Tool без записи в {@code tool-scopes} отклоняется
 * (fail-closed). Отказы пишутся в аудит. Остальные методы MCP требуют только валидный токен.
 */
final class ToolScopeFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(ToolScopeFilter.class);
  private static final String MCP_PATH = "/mcp";

  private final McpSecurityProperties properties;
  private final ToolCallAuditor auditor;
  private final JsonMapper mapper = new JsonMapper();

  ToolScopeFilter(McpSecurityProperties properties, ToolCallAuditor auditor) {
    this.properties = properties;
    this.auditor = auditor;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !MCP_PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!isSupportedCharset(request.getCharacterEncoding())) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    long limit = properties.maxRequestBytes().toBytes();
    byte[] body =
        request.getInputStream().readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 1) + 1);
    if (body.length > limit) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    JsonNode json;
    try {
      json = mapper.readTree(body);
    } catch (JacksonException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    if (json == null || !json.isObject()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    if ("tools/call".equals(json.path("method").asString(null))) {
      String tool = json.path("params").path("name").asString(null);
      String scope = tool == null ? null : properties.toolScopes().get(tool);
      if (scope == null) {
        LOG.warn("tools/call отклонён: для tool нет записи в archrag.mcp.security.tool-scopes");
        auditor.denied(tool, ToolDecision.DENIED_UNKNOWN_TOOL);
        forbid(request, response, null);
        return;
      }
      if (!hasAuthority("SCOPE_" + scope)) {
        auditor.denied(tool, ToolDecision.DENIED_SCOPE);
        forbid(request, response, scope);
        return;
      }
    }
    chain.doFilter(new BufferedRequest(request, body), response);
  }

  private static boolean isSupportedCharset(String name) {
    if (name == null) {
      return true;
    }
    try {
      return Charset.isSupported(name);
    } catch (IllegalCharsetNameException e) {
      return false;
    }
  }

  private static boolean hasAuthority(String authority) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      return false;
    }
    for (GrantedAuthority a : auth.getAuthorities()) {
      if (authority.equals(a.getAuthority())) {
        return true;
      }
    }
    return false;
  }

  private void forbid(HttpServletRequest request, HttpServletResponse response, String scope) {
    StringBuilder challenge = new StringBuilder("Bearer error=\"insufficient_scope\"");
    if (scope != null) {
      challenge.append(", scope=\"").append(scope).append('"');
    }
    challenge.append(", resource_metadata=\"").append(metadataUrl(request)).append('"');
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setHeader("WWW-Authenticate", challenge.toString());
  }

  private static String metadataUrl(HttpServletRequest request) {
    return ServletUriComponentsBuilder.fromRequestUri(request)
        .replacePath("/.well-known/oauth-protected-resource")
        .build()
        .toUriString();
  }

  /**
   * Запрос с уже прочитанным телом: поддерживает и {@code getInputStream()}, и {@code getReader()}.
   */
  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String enc = getCharacterEncoding();
      Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
      return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }
  }
}
