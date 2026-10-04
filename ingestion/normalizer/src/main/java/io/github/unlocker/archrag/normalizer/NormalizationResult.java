package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import java.util.List;

/** Итог нормализации одного события. */
public sealed interface NormalizationResult {

  /**
   * Событие нормализовано.
   *
   * <p>Инварианты: {@code commands} начинаются с {@code UpsertNode} записи события, источник-DTO в
   * них не просачивается; {@code null} и отсутствующие поля источника не превращаются в
   * {@code CloseAssertion} или обнуление — необязательные поля узла просто {@code null}, а
   * проектор не должен их записывать. {@code warnings} — коды потерянных данных (без значений).
   */
  record Normalized(
      List<GraphCommand> commands, List<UnresolvedReference> unresolved, List<String> warnings)
      implements NormalizationResult {

    public Normalized {
      commands = List.copyOf(commands);
      unresolved = List.copyOf(unresolved);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * Событие нельзя обработать; оно переводится в {@code QUARANTINED} с кодом и причиной, а raw
   * payload остаётся в хранилище.
   */
  record Quarantined(String errorCode, String reason) implements NormalizationResult {}
}
