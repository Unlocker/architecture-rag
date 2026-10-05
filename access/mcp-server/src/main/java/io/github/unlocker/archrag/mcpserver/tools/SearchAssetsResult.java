package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code search_assets}.
 *
 * @param items кандидаты: точные совпадения первыми, затем по убыванию score
 * @param truncated есть кандидаты сверх {@code limit} или результат обрезан бюджетом
 */
public record SearchAssetsResult(List<AssetHit> items, boolean truncated) {

  /**
   * Кандидат поиска.
   *
   * @param gid глобальный идентификатор узла
   * @param type каноническая метка
   * @param name отображаемое имя (name, hostname, url или code)
   * @param score релевантность; у {@code EXACT} фиксированно 1000
   * @param matchType {@code EXACT} или {@code FULLTEXT}
   * @param sources системы-источники активных SourceRecord
   * @param lastSeenAt время последнего наблюдения, ISO-8601 UTC
   */
  public record AssetHit(
      String gid,
      String type,
      String name,
      double score,
      String matchType,
      List<String> sources,
      String lastSeenAt) {}
}
