package io.github.unlocker.archrag.identityresolution;

import java.util.Objects;

/** Признак идентичности: вид и уже нормализованное значение (сырое значение источника не хранится). Значение не пустое. */
public record Feature(FeatureKind kind, String value) {

  public Feature {
    Objects.requireNonNull(kind, "kind");
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("feature value must not be blank");
    }
  }
}
