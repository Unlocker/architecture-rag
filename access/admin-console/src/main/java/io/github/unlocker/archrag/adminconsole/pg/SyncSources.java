package io.github.unlocker.archrag.adminconsole.pg;

import io.github.unlocker.archrag.adminconsole.api.InvalidRequestException;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import java.util.Arrays;
import java.util.List;

/**
 * Источники синхронизации и статусы событий: allowlist фильтров API.
 *
 * <p>Значения берутся из {@code ProcessingStatus} (event-schemas) и {@code SourceSystemCode}
 * (canonical-model): консоль не зависит от ingestion. В {@code inbox_event.source} лежит URN
 * {@value #URN_PREFIX}{@code <код>}, API работает с коротким кодом.
 */
public final class SyncSources {

  /** Префикс URN источника в {@code inbox_event.source} и {@code consumer_checkpoint.source}. */
  public static final String URN_PREFIX = SourceSystemCode.URN_PREFIX;

  /** Короткие коды источников в порядке {@code SourceSystemCode}. */
  public static final List<String> CODES =
      Arrays.stream(SourceSystemCode.values()).map(SourceSystemCode::code).toList();

  /** Статусы {@code inbox_event.status} в порядке {@code ProcessingStatus}. */
  public static final List<String> STATUSES =
      Arrays.stream(ProcessingStatus.values()).map(Enum::name).toList();

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
