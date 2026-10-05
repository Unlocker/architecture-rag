package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Расхождение свойства canonical-узла: неавторитетная запись {@code dissent} утверждает значение, отличное от
 * значения мастера по {@code AuthorityMatrix}. Строка {@code source_conflict}.
 *
 * <p>Инварианты: значения текстовые и сравниваются точно; {@code resolvedAt} не {@code null} только у
 * {@link Status#RESOLVED}; значения источника не логируются и в {@link #toString()} не попадают.
 *
 * @param gid canonical-узел
 * @param dissent неавторитетная запись
 * @param master запись мастера, с которой сравнивалось значение
 * @param status {@code OPEN}, пока значения расходятся
 * @param openedAt первое открытие (при повторном открытии не меняется)
 * @param resolvedAt момент, когда пара перестала расходиться, или {@code null}
 */
public record SourceConflict(
    UUID gid,
    String property,
    SourceKey dissent,
    String dissentValue,
    SourceKey master,
    String masterValue,
    Status status,
    Instant openedAt,
    Instant updatedAt,
    Instant resolvedAt) {

  /** Состояние конфликта. */
  public enum Status {
    OPEN,
    RESOLVED
  }

  public SourceConflict {
    Objects.requireNonNull(gid, "gid");
    Objects.requireNonNull(property, "property");
    Objects.requireNonNull(dissent, "dissent");
    Objects.requireNonNull(dissentValue, "dissentValue");
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(masterValue, "masterValue");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(openedAt, "openedAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** Без значений источника: они могут быть недоверенным текстом. */
  @Override
  public String toString() {
    return "SourceConflict[gid=" + gid + ", property=" + property + ", dissent=" + dissent + ", status=" + status + "]";
  }
}
