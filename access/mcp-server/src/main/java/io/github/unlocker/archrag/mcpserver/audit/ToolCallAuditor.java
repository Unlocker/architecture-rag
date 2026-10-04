package io.github.unlocker.archrag.mcpserver.audit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Пишет аудит-запись и метрики для каждого {@code tools/call}; общий для аспекта и фильтра scope.
 *
 * <p>Инварианты: одна запись лога (логгер {@value #LOGGER}) и одно наблюдение {@value
 * #OBSERVATION} на вызов. Токен, его значение и аргументы в теги метрик не попадают; тег {@code
 * tool} для неизвестного tool равен {@code unknown}, чтобы не плодить кардинальность из
 * пользовательского ввода. В {@code errorClass} пишется только имя класса исключения.
 */
@Component
public class ToolCallAuditor {

  static final String LOGGER = "archrag.audit.mcp";
  static final String OBSERVATION = "archrag.mcp.tool.call";
  static final String ROWS_SUMMARY = "archrag.mcp.tool.rows";
  static final int MAX_TOOL_NAME = 64;
  static final String UNKNOWN_TOOL_TAG = "unknown";

  private static final Logger LOG = LoggerFactory.getLogger(ToolCallAuditor.class);
  private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER);

  private final ObservationRegistry observations;
  private final MeterRegistry meters;

  ToolCallAuditor(ObservationRegistry observations, MeterRegistry meters) {
    this.observations = observations;
    this.meters = meters;
  }

  /** Запускает наблюдение вызова tool; завершается через {@link #completed}. */
  Observation start(String tool) {
    return Observation.createNotStarted(OBSERVATION, observations)
        .lowCardinalityKeyValue("tool", tool)
        .start();
  }

  /** Фиксирует вызов, дошедший до tool (успех, таймаут или ошибка), и останавливает наблюдение. */
  void completed(
      Observation observation,
      String tool,
      Map<String, Object> arguments,
      ToolCallContext context,
      Duration duration,
      ToolDecision decision,
      Throwable error) {
    // Вызывается из finally аспекта: сбой аудита не должен подменить ответ или исключение tool.
    try {
      observation.lowCardinalityKeyValue("decision", decision.name());
      observation.stop();
      DistributionSummary.builder(ROWS_SUMMARY)
          .tag("tool", tool)
          .register(meters)
          .record(context.rows());
      write(
          tool,
          ToolArguments.normalize(arguments),
          context.templates(),
          duration,
          context.rows(),
          context.truncated(),
          decision,
          error == null ? null : error.getClass().getName());
    } catch (RuntimeException e) {
      // Без аргументов вызова: они недоверенные.
      LOG.warn("Сбой записи аудита tool-вызова: {}", e.getClass().getName());
    }
  }

  /**
   * Фиксирует отказ до выполнения tool.
   *
   * @param tool имя из запроса или {@code null}; для {@link ToolDecision#DENIED_UNKNOWN_TOOL}
   *     в метрики идёт {@code unknown}
   */
  public void denied(String tool, ToolDecision decision) {
    String metricTool = decision == ToolDecision.DENIED_UNKNOWN_TOOL ? UNKNOWN_TOOL_TAG : tool;
    Observation observation = start(metricTool);
    observation.lowCardinalityKeyValue("decision", decision.name());
    observation.stop();
    write(tool, Map.of(), List.of(), Duration.ZERO, 0, false, decision, null);
  }

  private void write(
      String tool,
      Map<String, Object> arguments,
      List<String> templates,
      Duration duration,
      long rows,
      boolean truncated,
      ToolDecision decision,
      String errorClass) {
    AUDIT
        .atInfo()
        .addKeyValue("principal", principal())
        .addKeyValue("tool", sanitize(tool))
        .addKeyValue("arguments", arguments)
        .addKeyValue("templates", templates)
        .addKeyValue("durationMs", duration.toMillis())
        .addKeyValue("rows", rows)
        .addKeyValue("truncated", truncated)
        .addKeyValue("decision", decision)
        .addKeyValue("errorClass", errorClass)
        .log("mcp tool call");
  }

  /** Имя tool из запроса недоверенное: управляющие символы заменяются, длина до 64. */
  static String sanitize(String tool) {
    if (tool == null) {
      return null;
    }
    String cut = tool.length() > MAX_TOOL_NAME ? tool.substring(0, MAX_TOOL_NAME) + "…" : tool;
    return cut.replaceAll("\\p{Cntrl}", "?");
  }

  /** {@code sub} и {@code azp}/{@code client_id} из JWT; сам токен не читается. */
  private static String principal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwtAuth) {
      Jwt jwt = jwtAuth.getToken();
      String client = jwt.getClaimAsString("azp");
      if (client == null) {
        client = jwt.getClaimAsString("client_id");
      }
      String subject = "sub=" + jwt.getSubject();
      return client == null ? subject : subject + " client=" + client;
    }
    return auth == null ? "anonymous" : auth.getName();
  }
}
