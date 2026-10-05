package io.github.unlocker.archrag.graphprojector;

import java.util.List;
import java.util.Objects;

/**
 * Результат {@link GraphProjector#project}.
 *
 * @param skippedProperties свойства узла от неавторитетного источника, не записанные в граф
 *     ({@code Label.property}); основа для Conflict в E3
 * @param skippedRelations типы связей от неавторитетного источника, не записанные в граф
 */
public record ProjectionResult(
    ProjectionOutcome outcome, List<String> skippedProperties, List<String> skippedRelations) {

  public ProjectionResult {
    Objects.requireNonNull(outcome, "outcome");
    skippedProperties = List.copyOf(skippedProperties);
    skippedRelations = List.copyOf(skippedRelations);
  }

  static ProjectionResult of(ProjectionOutcome outcome) {
    return new ProjectionResult(outcome, List.of(), List.of());
  }
}
