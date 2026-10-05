package io.github.unlocker.archrag.sourcespi;

import java.util.List;

/**
 * Страница изменений.
 *
 * <p>Инвариант: {@code snapshotComplete} возможен только на последней странице полного прохода
 * ({@code hasMore == false}); только после него разрешена обработка missing set.
 *
 * @param changes изменения в порядке курсора
 * @param nextCursor непрозрачный курсор позиции после страницы; на последней странице им
 *     продолжают инкрементальный polling
 * @param hasMore есть ли ещё страницы в текущем проходе
 * @param snapshotComplete завершён ли полный проход (запрос с {@code cursor == null})
 */
public record ChangePage(
    List<SourceChange> changes, String nextCursor, boolean hasMore, boolean snapshotComplete) {

  public ChangePage {
    changes = List.copyOf(changes);
    if (snapshotComplete && hasMore) {
      throw new IllegalArgumentException("snapshotComplete is only valid on the last page");
    }
  }
}
