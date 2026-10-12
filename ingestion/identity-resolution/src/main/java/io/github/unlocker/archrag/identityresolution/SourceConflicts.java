package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище утверждений свойств и конфликтов источников.
 *
 * <p>Инварианты: сравниваются утверждения всех записей {@code gid}, а не состояние графа, поэтому результат не
 * зависит от порядка прихода мастера и неавторитетного источника; конфликт есть, только если у свойства есть
 * авторитетное утверждение и неавторитетное с другим значением (точное равенство строк); закрытый конфликт
 * не удаляется, а остаётся {@code RESOLVED}; повтор того же вызова не меняет состояние. Canonical-значение
 * здесь не меняется: его выбирает проектор.
 */
public interface SourceConflicts {

  /**
   * Заменяет утверждения записи, пересчитывает конфликты {@code gid}.
   *
   * @param values свойства записи в текстовом виде; {@code null}-свойства не передаются
   * @return открытые конфликты {@code gid} после пересчёта
   * @throws IdentityStoreException при отказе хранилища
   */
  List<SourceConflict> assertProperties(SourceKey key, UUID gid, NodeLabel label, Map<String, String> values);

  /**
   * Сверяет ссылку записи {@code dissent} с ключом, который известен из другого источника (конфликт вида
   * {@code RELATION}). Строка ключуется {@code (gid, property, dissent)}; граф не меняется.
   *
   * <p>{@code master} пуст (вторая сторона неизвестна): открытые {@code RELATION}-строки этой записи закрываются,
   * новые не открываются. Значения равны: строка закрывается. Значения расходятся: upsert {@code OPEN}
   * (повторное открытие сохраняет {@code opened_at}). Конфликты свойств не затрагиваются.
   *
   * @param dissent запись, чья ссылка проверяется
   * @param gid canonical-узел записи
   * @param property имя свойства, несущего ссылку
   * @param dissentValue значение ссылки в {@code dissent}
   * @param master запись второй стороны, если известна
   * @param masterValue значение той же ссылки у второй стороны; игнорируется при пустом {@code master}
   * @return открытые конфликты {@code gid} (всех видов) после пересчёта
   * @throws IdentityStoreException при отказе хранилища
   */
  List<SourceConflict> assertRelation(
      SourceKey dissent, UUID gid, String property, String dissentValue, Optional<SourceKey> master, String masterValue);

  /**
   * Удаляет утверждения записи (tombstone), закрывает {@code RELATION}-конфликты, где она {@code dissent},
   * пересчитывает конфликты и возвращает открытые.
   */
  List<SourceConflict> retract(SourceKey key, UUID gid);

  /** Открытые конфликты {@code gid}. */
  List<SourceConflict> openConflicts(UUID gid);
}
