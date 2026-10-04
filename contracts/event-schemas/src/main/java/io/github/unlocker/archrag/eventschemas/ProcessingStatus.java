package io.github.unlocker.archrag.eventschemas;

/**
 * Состояние обработки события в журнале (vision, «Состояния обработки»). Конечные: {@code PROJECTED},
 * {@code SUPERSEDED}, {@code DUPLICATE}, {@code IGNORED_OLD_VERSION}; {@code QUARANTINED} требует
 * решения оператора.
 */
public enum ProcessingStatus {
  RECEIVED,
  VALIDATED,
  NORMALIZED,
  RESOLVED,
  PROJECTED,
  QUARANTINED,
  RETRYING,
  SUPERSEDED,
  DUPLICATE,
  IGNORED_OLD_VERSION
}
