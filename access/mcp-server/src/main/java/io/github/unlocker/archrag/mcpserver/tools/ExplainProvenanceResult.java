package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code explain_provenance}. Значений свойств не содержит: расхождение показано записями
 * (мастер и диссидент) с версией, {@code fetchedAt} и {@code contentHash}.
 *
 * @param gid глобальный идентификатор узла
 * @param type каноническая метка
 * @param property запрошенное свойство (прошло allowlist-валидацию) или {@code null}
 * @param isCurrent {@code false} у закрытого узла
 * @param deletedAt момент закрытия узла или {@code null}
 * @param authorities источники-мастера по authority matrix для узла или свойства; пуст для неизвестной метки
 * @param conflictState {@code OPEN}, если у какой-либо записи есть открытый конфликт (по {@code property},
 *     если он задан), иначе {@code NONE}
 * @param records записи источников, активные первыми; неактивные (tombstone) тоже
 */
public record ExplainProvenanceResult(
    String gid,
    String type,
    String property,
    boolean isCurrent,
    String deletedAt,
    List<String> authorities,
    String conflictState,
    List<RecordRef> records) {

  /**
   * Запись источника, утверждающая узел.
   *
   * @param confidence уверенность утверждения с ребра {@code ASSERTS}
   * @param authority {@code MASTER} или {@code SUPPLEMENTARY}
   * @param authoritative {@code true}, если источник записи входит в {@code authorities}
   * @param active {@code false} у закрытой (tombstone) записи
   * @param deletedAt момент закрытия записи или {@code null}
   * @param conflictProperties имена свойств, по которым запись расходится с мастером (без значений)
   */
  public record RecordRef(
      String source,
      String sourceType,
      String sourceId,
      String sourceVersion,
      String fetchedAt,
      String contentHash,
      Double confidence,
      String authority,
      boolean authoritative,
      boolean active,
      String deletedAt,
      List<String> conflictProperties) {}
}
