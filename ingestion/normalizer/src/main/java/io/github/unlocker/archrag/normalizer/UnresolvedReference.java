package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.Objects;

/**
 * Битая ссылка: {@code from} через поле {@code field} ссылается на {@code target}, которого нет.
 * Вместо связи и фиктивного узла normalizer отдаёт эту запись; проектор сохраняет {@code relation} как
 * отложенную и строит связь, когда цель появится.
 *
 * @param from source record, содержащий ссылку
 * @param field имя поля источника со ссылкой (без значения)
 * @param relationType тип связи, которую нельзя построить
 * @param target ключ отсутствующей цели
 * @param relation полная спецификация отложенной связи (оба конца, свойства, {@code Validity})
 */
public record UnresolvedReference(SourceKey from, String field, RelationType relationType, SourceKey target,
    UpsertRelation relation) {

  public UnresolvedReference {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(relationType, "relationType");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(relation, "relation");
  }
}
