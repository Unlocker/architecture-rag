package io.github.unlocker.archrag.adminconsole.pg;

import io.github.unlocker.archrag.adminconsole.api.InvalidRequestException;

/**
 * Проверенные {@code page}/{@code size}. Инвариант: {@code page >= 0}, {@code 1 <= size <= }{@value #MAX_SIZE}.
 */
public record PageRequest(int page, int size) {

  public static final int DEFAULT_SIZE = 50;
  public static final int MAX_SIZE = 200;

  /** Значения по умолчанию для {@code null}; вне диапазона даёт {@link InvalidRequestException}. */
  public static PageRequest of(Integer page, Integer size) {
    int p = page == null ? 0 : page;
    int s = size == null ? DEFAULT_SIZE : size;
    if (p < 0) {
      throw new InvalidRequestException("page must be >= 0");
    }
    if (s < 1 || s > MAX_SIZE) {
      throw new InvalidRequestException("size must be in 1.." + MAX_SIZE);
    }
    return new PageRequest(p, s);
  }

  long offset() {
    return (long) page * size;
  }
}
