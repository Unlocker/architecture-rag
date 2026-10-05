package io.github.unlocker.archrag.graphquerycore;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Результат исполнения шаблона.
 *
 * <p>Инвариант: {@code rowCount == rows.size()}; {@code truncated} означает, что часть строк
 * отброшена из-за бюджета.
 *
 * @param templateId ID исполненного шаблона (для аудита)
 * @param rows строки в виде простых Java-структур (Map/List/скаляры)
 * @param truncated результат обрезан бюджетом строк или байт
 * @param rowCount число возвращённых строк
 * @param elapsed время исполнения
 */
public record QueryResult(
    String templateId,
    List<Map<String, Object>> rows,
    boolean truncated,
    int rowCount,
    Duration elapsed) {

  public QueryResult {
    rows = List.copyOf(rows);
  }
}
