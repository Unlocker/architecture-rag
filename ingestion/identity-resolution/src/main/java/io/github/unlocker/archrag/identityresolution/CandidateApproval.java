package io.github.unlocker.archrag.identityresolution;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.time.Instant;
import java.util.Objects;

/**
 * Подтверждение оператором кандидата на совпадение: запись второй стороны переносится на {@code gid} выжившей.
 * Пара неупорядоченная; {@code survivor} относится к порядку, в котором ключи заданы здесь (не к нормализованному
 * порядку хранения). {@code approvedBy}, {@code reason} и {@code approvedAt} — обязательный аудит.
 */
public record CandidateApproval(
    SourceKey left, SourceKey right, Survivor survivor, String approvedBy, String reason, Instant approvedAt) {

  /** Сторона пары, чей {@code gid} остаётся. */
  public enum Survivor {
    LEFT,
    RIGHT
  }

  public CandidateApproval {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    if (left.equals(right)) {
      throw new IllegalArgumentException("candidate must consist of two different source keys");
    }
    Objects.requireNonNull(survivor, "survivor");
    requireText(approvedBy, "approvedBy");
    requireText(reason, "reason");
    Objects.requireNonNull(approvedAt, "approvedAt");
  }

  /** Ключ, чей {@code gid} остаётся. */
  public SourceKey survivorKey() {
    return survivor == Survivor.LEFT ? left : right;
  }

  /** Ключ, чья запись переносится на {@code gid} выжившего. */
  public SourceKey movedKey() {
    return survivor == Survivor.LEFT ? right : left;
  }
}
