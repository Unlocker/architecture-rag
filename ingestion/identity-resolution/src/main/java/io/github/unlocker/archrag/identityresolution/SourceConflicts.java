package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Map;
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

  /** Удаляет утверждения записи (tombstone), пересчитывает конфликты и возвращает открытые. */
  List<SourceConflict> retract(SourceKey key, UUID gid);

  /** Открытые конфликты {@code gid}. */
  List<SourceConflict> openConflicts(UUID gid);
}
