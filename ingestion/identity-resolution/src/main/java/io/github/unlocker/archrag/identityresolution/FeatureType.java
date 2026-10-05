package io.github.unlocker.archrag.identityresolution;

import java.math.BigDecimal;

/**
 * Тип признака идентичности и его вес в score пары.
 *
 * <p>{@link #OWNER} только усиливает пару: сам по себе он кандидата не создаёт.
 */
public enum FeatureType {
  HOSTNAME("0.9"),
  REPOSITORY_URL("0.9"),
  NAME("0.6"),
  OWNER("0.3");

  private final BigDecimal weight;

  FeatureType(String weight) {
    this.weight = new BigDecimal(weight);
  }

  /** Вес признака, {@code 0 < weight < 1}. */
  public BigDecimal weight() {
    return weight;
  }

  /** Создаёт ли совпадение только этого признака кандидата. */
  public boolean standalone() {
    return this != OWNER;
  }
}
