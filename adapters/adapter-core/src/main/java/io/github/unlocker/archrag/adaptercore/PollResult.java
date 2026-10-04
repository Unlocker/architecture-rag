package io.github.unlocker.archrag.adaptercore;

/**
 * Итог одного цикла polling.
 *
 * @param outcome чем закончился цикл
 * @param appended сколько изменений записано в inbox за цикл (включая дубликаты)
 * @param syncRunId прогон полного snapshot, иначе {@code null}
 */
public record PollResult(Outcome outcome, long appended, String syncRunId) {

  /** Исход цикла. */
  public enum Outcome {
    /** Новых изменений нет, checkpoint не менялся. */
    UP_TO_DATE,
    /** Страницы записаны, checkpoint сдвинут. */
    ADVANCED,
    /** Полный snapshot завершён: маркер записан, checkpoint переведён на инкрементальный курсор. */
    SNAPSHOT_COMPLETED,
    /** Источник остался недоступен после всех попыток; checkpoint не менялся. */
    GAVE_UP,
    /** Другой цикл этого источника ещё идёт; ничего не читалось. */
    ALREADY_RUNNING
  }
}
