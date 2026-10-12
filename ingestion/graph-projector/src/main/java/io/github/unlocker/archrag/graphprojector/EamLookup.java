package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.List;
import java.util.Optional;

/**
 * Порт чтения графа для сверки платформы ИТ-системы EAM с платформой её решения (ADR 0031). Реализует
 * {@link GraphProjector}. Отдельно от {@code DeploymentLookup}: тот относится к deploy map.
 *
 * <p>Инварианты: читаются только активные записи EAM и открытые связи ({@code validTo IS NULL}); результат
 * — снимок на момент вызова; значения из источника не логируются.
 */
public interface EamLookup {

  /** Порт без данных: ничего не находит. */
  EamLookup NONE =
      new EamLookup() {
        @Override
        public Optional<SolutionPlatform> solutionPlatform(SourceKey solution) {
          return Optional.empty();
        }

        @Override
        public List<SystemPlatform> systemsOfSolution(SourceKey solution) {
          return List.of();
        }
      };

  /** Платформа решения: {@code Solution.platformId} узла активной записи EAM {@code SOLUTION}; {@code platformId} может быть {@code null}. */
  record SolutionPlatform(SourceKey solution, Long platformId) {}

  /** Платформа системы: {@code ITSystem.platformId} узла активной записи EAM {@code IT_SYSTEM}; может быть {@code null}. */
  record SystemPlatform(SourceKey system, Long platformId) {}

  /** Активная запись EAM {@code SOLUTION} с данным ключом; пусто, если решение неизвестно или удалено. */
  Optional<SolutionPlatform> solutionPlatform(SourceKey solution);

  /** Активные записи EAM {@code IT_SYSTEM}, чей узел имеет открытую {@code USES_SOLUTION} на узел решения. */
  List<SystemPlatform> systemsOfSolution(SourceKey solution);
}
