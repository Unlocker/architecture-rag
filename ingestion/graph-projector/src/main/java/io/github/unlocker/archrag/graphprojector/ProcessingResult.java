package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Objects;

/**
 * Итог обработки одного события {@link EventProcessor}.
 *
 * @param status статус события в журнале после обработки
 * @param errorCode код ошибки для {@code QUARANTINED}/{@code RETRYING}, иначе {@code null}
 * @param projection результат записи в граф или {@code null}, если до записи не дошло
 */
public record ProcessingResult(ProcessingStatus status, String errorCode, ProjectionResult projection) {

  public ProcessingResult {
    Objects.requireNonNull(status, "status");
  }
}
