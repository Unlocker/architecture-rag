package io.github.unlocker.archrag.ingestionservice;

import java.util.UUID;

/**
 * Результат по одному элементу загрузки crosswalk.
 *
 * @param index позиция элемента в запросе
 * @param status {@code APPLIED}, {@code CONFLICT} (ключи уже на разных {@code gid}) или {@code INVALID}
 * @param gid общий {@code gid} для {@code APPLIED}, иначе {@code null}
 */
public record CrosswalkResult(int index, Status status, UUID gid) {

  /** Исход элемента. */
  public enum Status {
    APPLIED,
    CONFLICT,
    INVALID
  }
}
