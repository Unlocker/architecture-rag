package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;
import java.util.Map;

/**
 * Ответ {@code get_asset}.
 *
 * @param gid глобальный идентификатор узла
 * @param type каноническая метка
 * @param properties свойства узла без служебных ({@code gid}, {@code isCurrent}, {@code firstSeenAt},
 *     {@code lastSeenAt}, {@code deletedAt}); времена в ISO-8601 UTC
 * @param firstSeenAt первое наблюдение, ISO-8601 UTC
 * @param lastSeenAt последнее наблюдение, ISO-8601 UTC
 * @param deletedAt момент закрытия узла или {@code null}
 * @param isCurrent {@code false} у закрытого узла
 * @param sources записи источников, активные первыми; неактивные показывают, почему узел закрыт
 * @param relations действующие связи 1 уровня; пуст при {@code includeRelations=false}
 * @param truncated связи обрезаны бюджетом
 */
public record GetAssetResult(
    String gid,
    String type,
    Map<String, Object> properties,
    String firstSeenAt,
    String lastSeenAt,
    String deletedAt,
    boolean isCurrent,
    List<SourceRef> sources,
    List<RelationRef> relations,
    boolean truncated) {

  /**
   * Запись источника, утверждающая узел.
   *
   * @param authority {@code MASTER} или {@code SUPPLEMENTARY}
   * @param active {@code false} у закрытой (tombstone) записи
   */
  public record SourceRef(
      String source,
      String sourceType,
      String sourceId,
      String sourceVersion,
      String fetchedAt,
      boolean active,
      String authority) {}

  /**
   * Связь 1 уровня.
   *
   * @param relationType тип связи из allowlist
   * @param direction {@code OUT} (связь выходит из актива) или {@code IN}
   * @param gid соседа: ID для следующих вызовов
   * @param isCurrent актуальность соседа
   * @param validFrom начало действия связи, ISO-8601 UTC
   */
  public record RelationRef(
      String relationType,
      String direction,
      String gid,
      String type,
      String name,
      boolean isCurrent,
      String validFrom) {}
}
