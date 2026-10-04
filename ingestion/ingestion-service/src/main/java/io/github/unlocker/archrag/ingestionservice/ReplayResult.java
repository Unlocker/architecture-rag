package io.github.unlocker.archrag.ingestionservice;

import java.util.Map;

/**
 * Итог replay.
 *
 * @param replayId идентификатор прогона; он же в {@code eventId} созданных строк журнала ({@code replay:<id>:<исходный id>})
 * @param total сколько строк журнала просмотрено
 * @param statuses счётчики по итоговому статусу; {@code SKIPPED_SNAPSHOT_MARKER} и {@code SKIPPED_NO_RAW}
 *     — события, которые не переигрываются
 */
public record ReplayResult(String replayId, long total, Map<String, Long> statuses) {}
