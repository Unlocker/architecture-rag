package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SyncRunStatus;
import java.util.OptionalLong;

/** Порт закрытия прогона {@code SyncRun} по итогу обработки маркера {@code snapshot-complete}. */
public interface SyncRunClosing {

  /** Ничего не делает: закрытие прогонов не подключено. */
  SyncRunClosing NONE = (source, runId, markerEventId, status, fetched) -> {};

  /**
   * Закрывает прогон {@code runId} источника {@code source} (значение из журнала, {@code urn:corp:eam}).
   *
   * @param markerEventId id маркера; его строка в счётчики не входит
   * @param status {@code SUCCEEDED} или {@code FAILED}
   * @param fetched {@code objectCount} маркера; пусто, если маркер его не нёс (в счётчик идёт 0)
   */
  void close(String source, String runId, String markerEventId, SyncRunStatus status, OptionalLong fetched);
}
