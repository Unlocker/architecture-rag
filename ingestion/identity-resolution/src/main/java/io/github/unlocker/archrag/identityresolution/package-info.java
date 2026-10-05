/**
 * Модуль identity-resolution: deterministic mapping {@code (source, sourceType, sourceId) -> gid} и
 * approved crosswalk в PostgreSQL.
 *
 * <p>Правила: записи с разными {@code gid} не сливаются, crosswalk между ними завершается
 * {@code CrosswalkConflictException} (merge — отдельная операция E3). Projector не держит собственного
 * mapping и получает {@code gid} только через {@code IdentityMapping}.
 *
 * <p>Третья ступень ({@code IdentityCandidates}): по признакам hostname, repository URL, имени и владельцу
 * записывается {@code IdentityCandidate} со score. Сравнение только на точное равенство нормализованных
 * значений внутри семейства меток; кандидат лишь записывается, узлы не объединяются, {@code gid} не
 * меняется, подтверждение остаётся за approved crosswalk. Повторное наблюдение идемпотентно.
 */
package io.github.unlocker.archrag.identityresolution;
