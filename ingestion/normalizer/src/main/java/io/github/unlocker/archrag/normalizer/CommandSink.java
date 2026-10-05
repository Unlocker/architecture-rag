package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Накопитель результата маппера. Связь добавляется только если цель известна
 * {@link ReferenceResolver}; иначе фиксируется {@link UnresolvedReference} и фиктивный узел не создаётся.
 */
public final class CommandSink {

  private final SourceKey self;
  private final ReferenceResolver resolver;
  private final List<GraphCommand> commands = new ArrayList<>();
  private final List<UnresolvedReference> unresolved = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();

  CommandSink(SourceKey self, ReferenceResolver resolver) {
    this.self = self;
    this.resolver = resolver;
  }

  /** Добавляет команду как есть (узел самой записи). */
  public void add(GraphCommand command) {
    commands.add(command);
  }

  /**
   * Добавляет связь {@code from → to} типа {@code type}, утверждаемую записью события, либо
   * {@link UnresolvedReference}, если второй конец связи (не сама запись) неизвестен.
   *
   * @param field поле источника, из которого взята ссылка
   */
  public void relation(
      RelationType type,
      SourceKey from,
      NodeLabel fromLabel,
      SourceKey to,
      NodeLabel toLabel,
      Validity validity,
      String field) {
    SourceKey other = from.equals(self) ? to : from;
    UpsertRelation relation = new UpsertRelation(type, from, fromLabel, to, toLabel, Map.of(), validity, self);
    if (!resolver.exists(other)) {
      unresolved.add(new UnresolvedReference(self, field, type, other, relation));
      return;
    }
    commands.add(relation);
  }

  /**
   * Добавляет связь {@code from → to}, обе стороны которой — внешние по отношению к записи события
   * (запись сама является связью). Если сторона неизвестна, для неё фиксируется
   * {@link UnresolvedReference}; связь строится, только когда известны обе.
   *
   * @param properties свойства связи; передаются только присутствующие в источнике
   * @param fromField поле источника со ссылкой на {@code from}
   * @param toField поле источника со ссылкой на {@code to}
   */
  public void link(
      RelationType type,
      SourceKey from,
      NodeLabel fromLabel,
      SourceKey to,
      NodeLabel toLabel,
      Map<String, Object> properties,
      Validity validity,
      String fromField,
      String toField) {
    UpsertRelation relation = new UpsertRelation(type, from, fromLabel, to, toLabel, properties, validity, self);
    boolean fromKnown = resolver.exists(from);
    boolean toKnown = resolver.exists(to);
    if (!fromKnown) {
      unresolved.add(new UnresolvedReference(self, fromField, type, from, relation));
    }
    if (!toKnown) {
      unresolved.add(new UnresolvedReference(self, toField, type, to, relation));
    }
    if (fromKnown && toKnown) {
      commands.add(relation);
    }
  }

  /** Фиксирует предупреждение (код без значений из источника). */
  public void warn(String warning) {
    warnings.add(warning);
  }

  NormalizationResult.Normalized result() {
    return new NormalizationResult.Normalized(commands, unresolved, warnings);
  }
}
