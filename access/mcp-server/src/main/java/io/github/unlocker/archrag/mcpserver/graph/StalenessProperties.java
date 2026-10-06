package io.github.unlocker.archrag.mcpserver.graph;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Порог устаревания фактов: {@code archrag.query.stale-after}.
 *
 * @param staleAfter узел устарел, если его {@code lastSeenAt} старше этого срока (или не задан)
 */
@ConfigurationProperties("archrag.query")
public record StalenessProperties(@DefaultValue("P7D") Duration staleAfter) {

  public StalenessProperties {
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("archrag.query.stale-after must be positive");
    }
  }
}
