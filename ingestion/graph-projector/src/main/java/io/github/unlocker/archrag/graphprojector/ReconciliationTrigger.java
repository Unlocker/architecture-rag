package io.github.unlocker.archrag.graphprojector;

/**
 * Порт передачи маркера {@code snapshot-complete} в reconciliation (E1.6, UNLOCKER-169): после него
 * разрешено обрабатывать missing set. Projector маркер не проецирует.
 */
@FunctionalInterface
public interface ReconciliationTrigger {

  /** Ничего не делает: пока reconciliation не подключён. */
  ReconciliationTrigger NOOP = (source, syncRunId, eventId, objectCount) -> {};

  /**
   * Полный snapshot {@code syncRunId} источника {@code source} завершён.
   *
   * @param eventId id события-маркера
   * @param objectCount сколько событий записал адаптер в прогон; меньше найденного в журнале быть не может
   */
  void snapshotComplete(String source, String syncRunId, String eventId, long objectCount);
}
