package io.github.unlocker.archrag.graphprojector;

/**
 * Маркер {@code snapshot-complete} нельзя применять: reconciliation отказывается до первой записи, повтор того же
 * маркера ничего не изменит, поэтому {@link EventProcessor} уводит его в {@code QUARANTINED} с {@link #code()}.
 * Сообщение не содержит значений из источника.
 */
public final class SnapshotRejectedException extends RuntimeException {

  /** В маркере нет валидного {@code objectCount}: полноту прогона проверить нечем. */
  public static final String MISSING_OBJECT_COUNT = "SNAPSHOT_OBJECT_COUNT_MISSING";

  /** Missing set больше допустимой доли активных записей источника: похоже на неполную выборку. */
  public static final String MASS_CLOSE_LIMIT = "SNAPSHOT_MASS_CLOSE_LIMIT";

  private final String code;

  public SnapshotRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** Стабильный код ошибки для {@code errorCode} журнала и DLQ. */
  public String code() {
    return code;
  }
}
