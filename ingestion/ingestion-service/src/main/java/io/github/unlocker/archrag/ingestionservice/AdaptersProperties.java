package io.github.unlocker.archrag.ingestionservice;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Управляющие адреса adapter-сервисов: {@code archrag.adapters.<source>.control-url}, где {@code <source>} —
 * {@code eam}, {@code scm}, {@code cmdb} или {@code deploymap}, а значение — полный URL контекста
 * {@code /control/snapshot}. Адреса только во внутренней сети compose.
 */
@ConfigurationProperties("archrag")
public record AdaptersProperties(Map<String, Adapter> adapters) {

  public AdaptersProperties {
    adapters = adapters == null ? Map.of() : Map.copyOf(adapters);
  }

  /** Адрес управляющего контекста одного адаптера. */
  public record Adapter(String controlUrl) {}
}
