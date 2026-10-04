package io.github.unlocker.archrag.sourcespi;

import java.util.Optional;

/**
 * Контракт чтения мастер-системы для адаптеров.
 *
 * <p>Реализация не пишет в источник и не выполняет побочных эффектов, кроме чтения. Недоступность
 * источника выражается {@link SourceUnavailableException}, а не пустым результатом: пустой ответ
 * означает «изменений нет» и не должен приводить к удалению данных.
 */
public interface SourceConnector {

  /** Источник, который обслуживает коннектор. */
  SourceSystem system();

  /**
   * Читает страницу изменений после курсора.
   *
   * @param cursor {@code nextCursor} предыдущей страницы; {@code null} — полный snapshot с начала
   * @param limit максимум изменений на странице, положительное число
   * @throws SourceUnavailableException при 429/5xx/timeout
   */
  ChangePage fetchChanges(String cursor, int limit);

  /**
   * Читает актуальное состояние объекта («GET by id» после webhook). Для удалённого объекта
   * возвращается изменение с {@link ChangeOperation#DELETE}.
   *
   * @return состояние объекта; пусто, если источник объект не знает
   * @throws SourceUnavailableException при 429/5xx/timeout
   */
  Optional<SourceChange> fetchById(String sourceType, String sourceId);
}
