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
    if (!resolver.exists(other)) {
      unresolved.add(new UnresolvedReference(self, field, type, other));
      return;
    }
    commands.add(new UpsertRelation(type, from, fromLabel, to, toLabel, Map.of(), validity, self));
  }

  /** Фиксирует предупреждение (код без значений из источника). */
  public void warn(String warning) {
    warnings.add(warning);
  }

  NormalizationResult.Normalized result() {
    return new NormalizationResult.Normalized(commands, unresolved, warnings);
  }
}
