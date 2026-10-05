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
   * проектор (E1.5, UNLOCKER-168) не должен их записывать. Время начала действия связи ({@code validity})
   * задаётся только если источник его передал; иначе оно {@code null} и выбор остаётся за проектором
   * (не перезаписывать открытую связь). {@code warnings} — коды потерянных данных (без значений).
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
