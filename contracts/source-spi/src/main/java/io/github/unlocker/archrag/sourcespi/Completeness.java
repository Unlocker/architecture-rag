package io.github.unlocker.archrag.sourcespi;

/**
 * Полнота записи: {@code PARTIAL} означает, что отсутствующие поля нельзя трактовать как
 * удаление.
 */
public enum Completeness {
  COMPLETE,
  PARTIAL
}
