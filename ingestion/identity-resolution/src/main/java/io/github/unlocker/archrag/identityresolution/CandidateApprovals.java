package io.github.unlocker.archrag.identityresolution;

/**
 * Подтверждение кандидата на совпадение (вариант «б» UNLOCKER-260): в {@code gid} выжившей стороны переносится только
 * запись второй стороны; история прежнего {@code gid} не сливается.
 *
 * <p>Инварианты: вся операция PostgreSQL — одна транзакция (перенос {@code identity_mapping}, {@code property_assertion},
 * закрытие {@code source_conflict} прежнего {@code gid} по записи, {@code approved_crosswalk}, статус кандидата);
 * подтвердить можно только {@code OPEN}-кандидата; остальные кандидаты не меняются. Граф здесь не трогается:
 * перепроекцию делает вызывающий по {@link CandidateRelocation}.
 */
public interface CandidateApprovals {

  /**
   * Переносит запись и закрывает кандидата ({@code RESOLVED}, {@code resolved_at}, {@code resolved_by}).
   *
   * @throws CandidateNotFoundException если для пары нет кандидата
   * @throws CandidateNotOpenException если кандидат уже {@code RESOLVED}
   * @throws IdentityStoreException при отказе хранилища
   */
  CandidateRelocation approve(CandidateApproval approval);
}
