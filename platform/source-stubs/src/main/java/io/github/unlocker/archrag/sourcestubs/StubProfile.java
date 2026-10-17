package io.github.unlocker.archrag.sourcestubs;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;

/**
 * Профиль данных заглушек ({@code ARCHRAG_STUB_PROFILE}): {@link #DEFAULT} — нынешний seed, на нём держатся тесты;
 * {@link #DEMO} — демо-набор {@link DemoSources}. Профиль выбирает данные всех пяти заглушек разом, поэтому смешать
 * профили между заглушками стенда нельзя, пока одно значение задаёт compose.
 */
public enum StubProfile {
  DEFAULT,
  DEMO;

  /**
   * Разбирает значение переменной окружения; пустое или {@code null} — {@link #DEFAULT}, неизвестное — {@link
   * IllegalArgumentException} с перечнем допустимых значений.
   */
  public static StubProfile parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    try {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("ARCHRAG_STUB_PROFILE must be one of default, demo");
    }
  }

  /** Четыре плоские заглушки с данными профиля. */
  public Map<SourceSystem, StubSource> sources(Clock clock) {
    return this == DEMO ? DemoSources.seeded(clock) : StubSources.seeded(clock);
  }

  /** Данные заглушки EAM API с данными профиля. */
  public EamApiStore eamApi(Clock clock) {
    return this == DEMO ? DemoSources.eamApi(clock) : EamApiSeed.seeded(clock);
  }
}
