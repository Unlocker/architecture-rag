package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.graphprojector.Reconciler;

/**
 * Итог повторного применения missing set.
 *
 * @param syncRunId прогон snapshot, чей маркер использован
 * @param markerEventId id маркера {@code snapshot-complete}
 */
public record ReconcileResult(String source, String syncRunId, String markerEventId, Reconciler.Report report) {}
