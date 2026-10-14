package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.Arrays;
import java.util.List;

/**
 * Сборка allowlist'ов шаблонов из {@code NodeLabel} и {@code RelationType}.
 *
 * <p>Списки остаются явными подмножествами: здесь только перевод enum в строки, так что переименование
 * метки или типа связи ломает компиляцию, а не молча расходится с шаблоном.
 */
final class Allowlists {

  private Allowlists() {}

  /** Метки узлов в порядке аргументов. */
  static List<String> labels(NodeLabel... labels) {
    return Arrays.stream(labels).map(NodeLabel::label).toList();
  }

  /** Имена типов связей в порядке аргументов. */
  static List<String> relations(RelationType... types) {
    return Arrays.stream(types).map(RelationType::name).toList();
  }
}
