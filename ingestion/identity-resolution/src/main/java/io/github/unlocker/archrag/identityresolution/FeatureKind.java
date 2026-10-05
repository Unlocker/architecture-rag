package io.github.unlocker.archrag.identityresolution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumSet;

/**
 * Вид признака идентичности, его вес и признак «сильный».
 *
 * <p>Веса зафиксированы в коде (не конфигурация): {@code score = min(1.0, сумма весов совпавших признаков)}.
 * {@link #OWNER} только повышает score: сам по себе кандидата не создаёт.
 */
public enum FeatureKind {
  HOSTNAME("0.6", true),
  REPOSITORY_URL("0.8", true),
  NAME("0.4", true),
  OWNER("0.2", false);

  private final BigDecimal weight;
  private final boolean strong;

  FeatureKind(String weight, boolean strong) {
    this.weight = new BigDecimal(weight);
    this.strong = strong;
  }

  /** Вес признака в score пары. */
  public BigDecimal weight() {
    return weight;
  }

  /** Достаточно ли совпадения одного этого признака, чтобы завести кандидата. */
  public boolean strong() {
    return strong;
  }

  /** {@code min(1.0, сумма весов)} по различным видам, шкала 2. */
  public static BigDecimal score(Collection<FeatureKind> matched) {
    EnumSet<FeatureKind> distinct = EnumSet.noneOf(FeatureKind.class);
    distinct.addAll(matched);
    BigDecimal sum = BigDecimal.ZERO;
    for (FeatureKind kind : distinct) {
      sum = sum.add(kind.weight);
    }
    return sum.min(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
  }
}
