package io.github.unlocker.archrag.graphprojector;

/** Итог проекции одного изменения. */
public enum ProjectionOutcome {
  /** Изменение записано в граф. */
  APPLIED,
  /** Версия равна применённой: граф не менялся (штатный повтор). */
  NOOP_SAME_VERSION,
  /** Версия ниже применённой: граф не менялся. */
  IGNORED_OLD_VERSION
}
