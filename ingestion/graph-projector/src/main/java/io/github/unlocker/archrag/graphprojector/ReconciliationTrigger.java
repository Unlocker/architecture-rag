package io.github.unlocker.archrag.graphprojector;

/**
 * Порт передачи маркера {@code snapshot-complete} в reconciliation (E1.6, UNLOCKER-169): после него
 * разрешено обрабатывать missing set. Projector маркер не проецирует.
 */
@FunctionalInterface
public interface ReconciliationTrigger {

  /** Ничего не делает: пока reconciliation не подключён. */
  ReconciliationTrigger NOOP = (source, syncRunId, eventId) -> {};

  /**
   * Полный snapshot {@code syncRunId} источника {@code source} завершён.
   *
   * @param eventId id события-маркера
   */
  void snapshotComplete(String source, String syncRunId, String eventId);
}
