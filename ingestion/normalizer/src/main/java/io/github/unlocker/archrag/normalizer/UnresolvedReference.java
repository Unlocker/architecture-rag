package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.Objects;

/**
 * Битая ссылка: {@code from} через поле {@code field} ссылается на {@code target}, которого нет.
 * Вместо связи и фиктивного узла normalizer отдаёт эту запись; связь строится, когда цель появится.
 *
 * @param from source record, содержащий ссылку
 * @param field имя поля источника со ссылкой (без значения)
 * @param relationType тип связи, которую нельзя построить
 * @param target ключ отсутствующей цели
 */
public record UnresolvedReference(SourceKey from, String field, RelationType relationType, SourceKey target) {

  public UnresolvedReference {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(relationType, "relationType");
    Objects.requireNonNull(target, "target");
  }
}
