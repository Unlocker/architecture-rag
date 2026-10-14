package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Итог {@link CandidateApprovals#approve}: что перенесено в PostgreSQL и что осталось сделать в графе.
 *
 * @param moved ключ, чья запись перенесена
 * @param survivor ключ, чей {@code gid} остался
 * @param fromGid прежний {@code gid} перенесённого ключа
 * @param intoGid {@code gid} выжившего; равен {@code fromGid}, если ключи уже делили {@code gid} (переноса не было)
 * @param sourceGidEmpty на {@code fromGid} не осталось ни одного ключа: его узел подлежит закрытию
 */
public record CandidateRelocation(SourceKey moved, SourceKey survivor, UUID fromGid, UUID intoGid, boolean sourceGidEmpty) {

  public CandidateRelocation {
    Objects.requireNonNull(moved, "moved");
    Objects.requireNonNull(survivor, "survivor");
    Objects.requireNonNull(fromGid, "fromGid");
    Objects.requireNonNull(intoGid, "intoGid");
  }

  /** {@code true}, если {@code gid} записи изменился и граф нужно перепроецировать. */
  public boolean changesGid() {
    return !fromGid.equals(intoGid);
  }
}
