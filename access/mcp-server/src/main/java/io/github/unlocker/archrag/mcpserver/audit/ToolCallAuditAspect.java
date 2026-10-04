package io.github.unlocker.archrag.mcpserver.audit;

import io.github.unlocker.archrag.graphquerycore.QueryTimeoutException;
import io.micrometer.observation.Observation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Оборачивает каждый метод {@link McpTool}: открывает {@link ToolCallContext}, замеряет вызов и
 * передаёт итог в {@link ToolCallAuditor}. Исключение tool не глушится, а пробрасывается дальше.
 */
@Aspect
@Component
public class ToolCallAuditAspect {

  private final ToolCallAuditor auditor;

  ToolCallAuditAspect(ToolCallAuditor auditor) {
    this.auditor = auditor;
  }

  @Around("@annotation(mcpTool)")
  Object audit(ProceedingJoinPoint joinPoint, McpTool mcpTool) throws Throwable {
    var signature = (MethodSignature) joinPoint.getSignature();
    String tool = mcpTool.name().isEmpty() ? signature.getName() : mcpTool.name();
    Map<String, Object> arguments = argumentsOf(signature.getParameterNames(), joinPoint.getArgs());
    ToolCallContext context = ToolCallContext.open();
    Observation observation = auditor.start(tool);
    long started = System.nanoTime();
    ToolDecision decision = ToolDecision.ALLOWED;
    Throwable failure = null;
    try {
      return joinPoint.proceed();
    } catch (Throwable e) {
      failure = e;
      decision = e instanceof QueryTimeoutException ? ToolDecision.TIMEOUT : ToolDecision.ERROR;
      throw e;
    } finally {
      ToolCallContext.close();
      auditor.completed(
          observation,
          tool,
          arguments,
          context,
          Duration.ofNanos(System.nanoTime() - started),
          decision,
          failure);
    }
  }

  private static Map<String, Object> argumentsOf(String[] names, Object[] values) {
    var arguments = new LinkedHashMap<String, Object>();
    for (int i = 0; i < values.length; i++) {
      arguments.put(names != null && i < names.length ? names[i] : "arg" + i, values[i]);
    }
    return arguments;
  }
}
