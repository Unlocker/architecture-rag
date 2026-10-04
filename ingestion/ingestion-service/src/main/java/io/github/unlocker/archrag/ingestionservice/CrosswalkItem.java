package io.github.unlocker.archrag.ingestionservice;

/**
 * Элемент запроса загрузки crosswalk. {@code approvedBy} и {@code approvedAt} в запросе не принимаются: это
 * {@code sub} токена и серверное время.
 */
public record CrosswalkItem(KeyDto left, KeyDto right, String reason) {

  /** Ключ source-записи. */
  public record KeyDto(String source, String sourceType, String sourceId) {}
}
