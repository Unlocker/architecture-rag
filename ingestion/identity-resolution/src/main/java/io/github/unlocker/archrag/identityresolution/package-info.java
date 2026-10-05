/**
 * Модуль identity-resolution: deterministic mapping {@code (source, sourceType, sourceId) -> gid} и
 * approved crosswalk в PostgreSQL.
 *
 * <p>Правила: записи с разными {@code gid} не сливаются, crosswalk между ними завершается
 * {@code CrosswalkConflictException} (merge — отдельная операция E3). Projector не держит собственного
 * mapping и получает {@code gid} только через {@code IdentityMapping}.
 */
package io.github.unlocker.archrag.identityresolution;
