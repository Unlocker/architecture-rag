package io.github.unlocker.archrag.adminconsole.pg;

import io.github.unlocker.archrag.adminconsole.api.InvalidRequestException;
import java.util.List;

/**
 * Источники синхронизации и статусы событий: allowlist фильтров API.
 *
 * <p>Значения продублированы из {@code ProcessingStatus} (event-schemas) и {@code SourceSystemCode}
 * (canonical-model): консоль не зависит от ingestion. В {@code inbox_event.source} лежит URN
 * {@value #URN_PREFIX}{@code <код>}, API работает с коротким кодом.
 */
public final class SyncSources {

  /** Префикс URN источника в {@code inbox_event.source} и {@code consumer_checkpoint.source}. */
  public static final String URN_PREFIX = "urn:corp:";

  /** Короткие коды источников в порядке отдачи. */
  public static final List<String> CODES = List.of("eam", "scm", "cmdb", "deploymap");

  /** Статусы {@code inbox_event.status} ({@code ProcessingStatus}). */
  public static final List<String> STATUSES =
      List.of(
          "RECEIVED",
          "VALIDATED",
          "NORMALIZED",
          "RESOLVED",
          "PROJECTED",
          "QUARANTINED",
          "RETRYING",
          "SUPERSEDED",
          "DUPLICATE",
          "IGNORED_OLD_VERSION");

  private SyncSources() {}

  /** Короткий код → значение колонки; {@code null} для {@code null}; неизвестный код — 400 без эха ввода. */
  public static String toStored(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    if (!CODES.contains(code)) {
      throw new InvalidRequestException("Unknown source; allowed: " + String.join(", ", CODES));
    }
    return URN_PREFIX + code;
  }

  /** Значение колонки → короткий код; чужие значения возвращаются как есть. */
  public static String toCode(String stored) {
    return stored != null && stored.startsWith(URN_PREFIX) ? stored.substring(URN_PREFIX.length()) : stored;
  }

  /** Статус из allowlist или {@code null}, если фильтр не задан. */
  public static String status(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    if (!STATUSES.contains(status)) {
      throw new InvalidRequestException("Unknown status; allowed: " + String.join(", ", STATUSES));
    }
    return status;
  }
}
