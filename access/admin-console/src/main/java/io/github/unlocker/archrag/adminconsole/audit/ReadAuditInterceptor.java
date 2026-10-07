package io.github.unlocker.archrag.adminconsole.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Аудит чтения: одна structured-запись на запрос {@code /api/**}, дошедший до контроллера.
 *
 * <p>Инварианты: логгер {@value #LOGGER}; поля {@code sub}, {@code endpoint} (метод и шаблон маршрута,
 * без query и значений path-переменных), {@code templates}, {@code rows}, {@code truncated},
 * {@code status}, {@code durationMs}. Токен и параметры запроса в запись не попадают.
 */
@Component
public class ReadAuditInterceptor implements HandlerInterceptor {

  static final String LOGGER = "archrag.audit.console";
  private static final String STARTED = ReadAuditInterceptor.class.getName() + ".started";

  private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER);

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    request.setAttribute(STARTED, System.nanoTime());
    request.setAttribute(ReadAuditContext.ATTRIBUTE, new ReadAuditContext());
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    try {
      var context = (ReadAuditContext) request.getAttribute(ReadAuditContext.ATTRIBUTE);
      long started = (Long) request.getAttribute(STARTED);
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      AUDIT
          .atInfo()
          .addKeyValue("sub", subject())
          .addKeyValue("endpoint", request.getMethod() + " " + (pattern == null ? "unmatched" : pattern))
          .addKeyValue("templates", context.templates())
          .addKeyValue("rows", context.rows())
          .addKeyValue("truncated", context.truncated())
          .addKeyValue("status", response.getStatus())
          .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
          .log("console read");
    } catch (RuntimeException e) {
      // Сбой аудита не должен менять ответ; без деталей запроса: они недоверенные.
      AUDIT.warn("Сбой записи аудита чтения: {}", e.getClass().getName());
    }
  }

  /** {@code sub} из JWT; сам токен не читается. */
  private static String subject() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwtAuth) {
      Jwt jwt = jwtAuth.getToken();
      return jwt.getSubject();
    }
    return auth == null ? "anonymous" : auth.getName();
  }
}
