package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import java.util.List;

/**
 * Порт приёма предупреждений нормализации ({@code Normalized.warnings()}). Нормализатор остаётся чистой функцией,
 * поэтому наружу предупреждения выводит {@link EventProcessor}; реализует слой метрик ingestion.
 *
 * <p>Инварианты: вызывается один раз на успешную нормализацию и только при непустом списке; в списке только коды,
 * без значений из источника; реализация не бросает исключений и не меняет граф и журнал.
 */
@FunctionalInterface
public interface NormalizationWarnings {

  /** Порт, который ничего не делает. */
  NormalizationWarnings NONE = (source, warnings) -> {};

  /** Принимает коды предупреждений нормализации одного события источника {@code source}. */
  void record(SourceSystemCode source, List<String> warnings);
}
