package io.github.unlocker.archrag.eventschemas;

/**
 * Состояние обработки события в журнале (vision, «Состояния обработки»). Конечные: {@code PROJECTED},
 * {@code DUPLICATE}, {@code IGNORED_OLD_VERSION}; {@code QUARANTINED} требует решения оператора.
 */
public enum ProcessingStatus {
  RECEIVED,
  VALIDATED,
  NORMALIZED,
  RESOLVED,
  PROJECTED,
  QUARANTINED,
  RETRYING,
  DUPLICATE,
  IGNORED_OLD_VERSION;

  /**
   * Допустим ли переход в {@code next}. Переход в себя разрешён всегда (идемпотентный повтор).
   * Из конечных состояний ({@code PROJECTED}, {@code DUPLICATE}, {@code IGNORED_OLD_VERSION}) других
   * переходов нет, из {@code QUARANTINED} выход только через {@code RETRYING} (решение оператора).
   */
  public boolean canTransitionTo(ProcessingStatus next) {
    if (this == next) {
      return true;
    }
    return switch (this) {
      case RECEIVED -> next == VALIDATED || next == RETRYING || next == QUARANTINED
          || next == DUPLICATE || next == IGNORED_OLD_VERSION;
      case VALIDATED -> next == NORMALIZED || next == RETRYING || next == QUARANTINED || next == IGNORED_OLD_VERSION;
      case NORMALIZED -> next == RESOLVED || next == RETRYING || next == QUARANTINED || next == IGNORED_OLD_VERSION;
      case RESOLVED -> next == PROJECTED || next == RETRYING || next == QUARANTINED || next == IGNORED_OLD_VERSION;
      case RETRYING -> next == VALIDATED || next == NORMALIZED || next == RESOLVED || next == PROJECTED
          || next == QUARANTINED || next == IGNORED_OLD_VERSION;
      case QUARANTINED -> next == RETRYING;
      case PROJECTED, DUPLICATE, IGNORED_OLD_VERSION -> false;
    };
  }
}
