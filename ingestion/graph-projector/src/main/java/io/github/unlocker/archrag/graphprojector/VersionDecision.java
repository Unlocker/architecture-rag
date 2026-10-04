package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.eventschemas.SourceVersion;

/**
 * Решение по версии: применять ли изменение к уже известной записи источника.
 *
 * <p>Версии сравниваются как числа ({@link SourceVersion}), не как строки: {@code 99} младше {@code 184}.
 * Правила: запись неизвестна — применить; версия ниже применённой — {@link #OLD}; равная — {@link #SAME}
 * (повтор того же изменения под другим {@code eventId}: webhook и polling, это не ошибка); выше — применить.
 * Исключение: tombstone с равной версией применяется к ещё активной записи, потому что удаление не обязано
 * повышать версию объекта.
 */
enum VersionDecision {
  APPLY,
  SAME,
  OLD;

  /** Решение для upsert. {@code applied == null} — запись ещё не применялась. */
  static VersionDecision forUpsert(SourceVersion incoming, SourceVersion applied) {
    if (applied == null) {
      return APPLY;
    }
    int cmp = incoming.compareTo(applied);
    return cmp > 0 ? APPLY : cmp == 0 ? SAME : OLD;
  }

  /** Решение для tombstone; {@code appliedActive} — активна ли применённая запись. */
  static VersionDecision forTombstone(SourceVersion incoming, SourceVersion applied, boolean appliedActive) {
    if (applied == null) {
      return APPLY;
    }
    int cmp = incoming.compareTo(applied);
    if (cmp == 0) {
      return appliedActive ? APPLY : SAME;
    }
    return cmp > 0 ? APPLY : OLD;
  }
}
