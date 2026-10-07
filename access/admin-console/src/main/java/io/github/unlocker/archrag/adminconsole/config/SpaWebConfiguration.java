package io.github.unlocker.archrag.adminconsole.config;

import io.github.unlocker.archrag.adminconsole.audit.ReadAuditInterceptor;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Раздача статики из {@code META-INF/resources} (бандл {@code admin-ui}) и SPA fallback на {@code index.html}.
 *
 * <p>Инвариант: fallback не срабатывает для {@code api/**} и {@code actuator/**}, а также для путей с
 * расширением файла: отсутствующий ресурс остаётся 404, а не HTML вместо JSON или JS.
 */
@Configuration(proxyBeanMethods = false)
public class SpaWebConfiguration implements WebMvcConfigurer {

  private static final String ROOT = "classpath:/META-INF/resources/";
  private static final Resource INDEX = new ClassPathResource("META-INF/resources/index.html");

  private final ReadAuditInterceptor audit;

  SpaWebConfiguration(ReadAuditInterceptor audit) {
    this.audit = audit;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations(ROOT)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new SpaFallbackResolver());
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(audit).addPathPatterns("/api/**");
  }

  /** Отдаёт файл, а для «маршрутов» SPA (без расширения, не api/actuator) — {@code index.html}. */
  static final class SpaFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource requested = location.createRelative(resourcePath);
      if (!resourcePath.isEmpty() && !resourcePath.endsWith("/") && requested.exists() && requested.isReadable()) {
        return requested;
      }
      if (isFallbackRoute(resourcePath) && INDEX.exists()) {
        return INDEX;
      }
      return null;
    }

    static boolean isFallbackRoute(String path) {
      String normalized = path.startsWith("/") ? path.substring(1) : path;
      if (normalized.equals("api") || normalized.startsWith("api/")
          || normalized.equals("actuator") || normalized.startsWith("actuator/")) {
        return false;
      }
      String last = normalized.substring(normalized.lastIndexOf('/') + 1);
      return !last.contains(".");
    }
  }
}
