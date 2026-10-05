package io.github.unlocker.archrag.identityresolution;

/**
 * Crosswalk связывает записи, которые уже отображены на разные {@code gid}. Автоматический merge
 * запрещён: это отдельная операция (E3), поэтому состояние не меняется.
 */
public class CrosswalkConflictException extends RuntimeException {

  public CrosswalkConflictException(String message) {
    super(message);
  }
}
