package io.github.unlocker.archrag.graphquerycore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Накапливает строки в пределах бюджета строк и байт.
 *
 * <p>Инварианты: из итератора читается не больше {@code maxRows + 1} элементов; лишняя строка и
 * строка, превысившая байтовый порог, отбрасываются, а результат помечается {@code truncated}.
 * Чистая логика без Neo4j.
 */
public final class BudgetedCollector {

  /**
   * Результат сбора.
   *
   * @param rows принятые строки
   * @param truncated часть строк отброшена из-за бюджета
   */
  public record Collected(List<Map<String, Object>> rows, boolean truncated) {}

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final int maxRows;
  private final long maxBytes;

  public BudgetedCollector(int maxRows, long maxBytes) {
    if (maxRows < 1 || maxBytes < 1) {
      throw new IllegalArgumentException("Budget must be positive");
    }
    this.maxRows = maxRows;
    this.maxBytes = maxBytes;
  }

  /** Собирает строки из итератора, не читая дальше {@code maxRows + 1}. */
  public Collected collect(Iterator<Map<String, Object>> source) {
    List<Map<String, Object>> rows = new ArrayList<>();
    long bytes = 0;
    int read = 0;
    while (read <= maxRows && source.hasNext()) {
      Map<String, Object> row = source.next();
      read++;
      if (read > maxRows) {
        return new Collected(rows, true);
      }
      bytes += serializedSize(row);
      if (bytes > maxBytes) {
        return new Collected(rows, true);
      }
      rows.add(row);
    }
    return new Collected(rows, false);
  }

  private static long serializedSize(Map<String, Object> row) {
    try {
      return MAPPER.writeValueAsBytes(row).length;
    } catch (JacksonException e) {
      throw new IllegalStateException("Cannot serialize result row", e);
    }
  }
}
