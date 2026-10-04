package io.github.unlocker.archrag.ingestionservice;

/** Последний маркер {@code snapshot-complete} ещё не обработан ({@code PROJECTED}): полнота прогона не подтверждена. */
public class SnapshotNotCompleteException extends RuntimeException {

  /** Код ошибки в ответе. */
  public static final String CODE = "SNAPSHOT_NOT_COMPLETE";

  public SnapshotNotCompleteException() {
    super(CODE);
  }
}
