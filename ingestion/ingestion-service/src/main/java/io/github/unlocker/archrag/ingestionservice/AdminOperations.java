package io.github.unlocker.archrag.ingestionservice;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Единый путь админской операции: замок, запись {@code STARTED} в аудит, выполнение, итог в аудит.
 *
 * <p>Инварианты: параллельно идёт одна операция ({@link AdminBusyException}); без записи в аудит операция не
 * начинается; сбой операции фиксируется как {@code FAILED} с классом исключения (текст исключения может содержать
 * значения из источника, поэтому в аудит и лог не идёт) и пробрасывается дальше.
 */
@Component
public class AdminOperations {

  private static final Logger LOG = LoggerFactory.getLogger(AdminOperations.class);

  private final AdminLock lock;
  private final AdminAudit audit;

  public AdminOperations(AdminLock lock, AdminAudit audit) {
    this.lock = lock;
    this.audit = audit;
  }

  /**
   * Выполняет {@code body} под замком с аудитом.
   *
   * @param request параметры запроса для аудита (без payload)
   * @param replayId идентификатор replay или {@code null}
   * @param summary счётчики результата для аудита
   */
  public <T> T run(
      String operation,
      String actor,
      Map<String, ?> request,
      String replayId,
      Supplier<T> body,
      Function<T, Map<String, ?>> summary) {
    try (AdminLock.Lease ignored = lock.acquire()) {
      long id = audit.start(operation, actor, request, replayId);
      LOG.info("admin operation started: operation={} actor={} replayId={}", operation, actor, replayId);
      T result;
      try {
        result = body.get();
      } catch (RuntimeException e) {
        audit.finish(id, false, null, e.getClass().getSimpleName());
        LOG.warn("admin operation failed: operation={} replayId={} error={}", operation, replayId, e.getClass().getSimpleName());
        throw e;
      }
      audit.finish(id, true, summary.apply(result), null);
      LOG.info("admin operation finished: operation={} replayId={}", operation, replayId);
      return result;
    }
  }

  /** Выполняет {@code body} под тем же замком, но без общей записи аудита: её ведёт вызывающий (по элементам). */
  public <T> T locked(Supplier<T> body) {
    try (AdminLock.Lease ignored = lock.acquire()) {
      return body.get();
    }
  }
}
