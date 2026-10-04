package io.github.unlocker.archrag.identityresolution;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Подтверждённое соответствие двух source-записей одной канонической сущности, например
 * {@code EAM/IT_SYSTEM/PAY ↔ SCM/CATALOG_SYSTEM/42}. Ключи различны; пара неупорядоченная.
 */
public record Crosswalk(SourceKey left, SourceKey right, String approvedBy, Instant approvedAt) {

  public Crosswalk {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    if (left.equals(right)) {
      throw new IllegalArgumentException("crosswalk must link two different source keys");
    }
    requireText(approvedBy, "approvedBy");
    Objects.requireNonNull(approvedAt, "approvedAt");
  }
}
