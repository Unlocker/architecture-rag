package io.github.unlocker.archrag.identityresolution;

import java.util.Objects;

/** Признак идентичности: тип и уже нормализованное значение. Значение не пустое. */
public record Feature(FeatureType type, String value) {

  public Feature {
    Objects.requireNonNull(type, "type");
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("feature value must not be blank");
    }
  }
}
