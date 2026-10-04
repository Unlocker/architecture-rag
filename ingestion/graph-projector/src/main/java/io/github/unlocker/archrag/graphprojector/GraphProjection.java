package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.util.List;
import java.util.Optional;

/** Порт записи в граф для {@link EventProcessor}; единственная реализация — {@link GraphProjector}. */
public interface GraphProjection {

  /** Применяет изменение одной транзакцией; повтор безопасен. */
  ProjectionResult project(ProjectionRequest request);

  /** Состояние применённой записи источника, если она уже есть в графе. */
  Optional<AppliedRecord> applied(SourceKey key);

  /** {@code true}, если в графе есть активная запись с таким ключом (для {@code ReferenceResolver}). */
  boolean isActive(SourceKey key);

  /**
   * Активные записи источника с применённой версией: основа missing set. Только чтение; записи, у которых
   * ещё нет версии (только заблокированные {@code MERGE}), не возвращаются.
   */
  List<ActiveRecord> activeRecords(SourceSystemCode source);

  /**
   * Активная запись источника.
   *
   * @param version применённая версия
   */
  record ActiveRecord(SourceKey key, SourceVersion version) {}

  /**
   * Применённая запись источника.
   *
   * @param version последняя применённая версия
   * @param active {@code false}, если запись удалена (tombstone)
   */
  record AppliedRecord(SourceVersion version, boolean active) {}
}
