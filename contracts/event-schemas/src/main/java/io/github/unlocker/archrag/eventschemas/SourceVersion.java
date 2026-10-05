package io.github.unlocker.archrag.eventschemas;

import java.math.BigInteger;

/**
 * Версия объекта в системе-источнике. Хранится строкой (как в CloudEvent), но сравнивается как
 * неотрицательное десятичное число: {@code "99"} меньше {@code "184"}. Инвариант: только цифры.
 * Источник с нечисловой версией отклоняется на валидации, а не сравнивается лексикографически.
 */
public record SourceVersion(String value) implements Comparable<SourceVersion> {

  public SourceVersion {
    if (value == null || !value.matches("[0-9]{1,40}")) {
      throw new IllegalArgumentException("sourceVersion must be a non-negative decimal number");
    }
  }

  @Override
  public int compareTo(SourceVersion other) {
    return new BigInteger(value).compareTo(new BigInteger(other.value));
  }

  /** Возвращает {@code true}, если эта версия строго старше {@code applied}. */
  public boolean isOlderThan(SourceVersion applied) {
    return compareTo(applied) < 0;
  }
}
