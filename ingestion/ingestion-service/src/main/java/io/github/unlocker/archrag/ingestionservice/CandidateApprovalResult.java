package io.github.unlocker.archrag.ingestionservice;

import java.util.UUID;

/**
 * Результат подтверждения кандидата на совпадение.
 *
 * @param status исход, см. {@link Status}
 * @param gid {@code gid} выжившего узла, на котором теперь запись
 * @param previousGid прежний {@code gid} перенесённой записи
 * @param previousRetired прежний узел закрыт (на его {@code gid} не осталось ключей) и получил {@code mergedInto}
 */
public record CandidateApprovalResult(Status status, UUID gid, UUID previousGid, boolean previousRetired) {

  /** Исход операции. */
  public enum Status {
    /** Запись перенесена и сразу перепроецирована на узел выжившего. */
    REPROJECTED,
    /** Запись перенесена в PostgreSQL; в графе она неактивна или ещё не проецировалась, перепроецировать нечего. */
    REMAPPED,
    /** Ключи уже делили {@code gid}: кандидат закрыт, переносить нечего. */
    UNCHANGED
  }
}
