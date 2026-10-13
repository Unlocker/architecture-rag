# gid-mapping-crosswalk: дизайн

> Ссылается на [`../design.md`](../design.md) (каркас транзакции, интеграция) и [`../contracts.md`](../contracts.md) (схема V2). Блок-схема `approve`: [`../../../flowcharts/identity-resolution.md`](../../flowcharts/identity-resolution.md).

## Сигнатуры
`PostgresIdentityMapping(DataSource)`: `find`, `resolve`, `keysOf`, `approve` (`IR/PostgresIdentityMapping.java:32-101`).

## Поток `resolve(key)` 🟢 (`:45-60`)
1. Соединение, `autoCommit = false`.
2. `getOrCreate(c, key, UUID.randomUUID())`: `INSERT INTO identity_mapping (source, source_type, source_id, gid, created_at) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING` (`created_at = Instant.now()`), затем `SELECT gid ...` по ключу; если строки нет — `IdentityStoreException("mapping vanished after insert", null)` (`:124-132`).
3. `commit`, возврат `gid`. Сбой SQL -> `rollback` + `IdentityStoreException("resolve failed", e)`.

`find`: единственный `SELECT gid`; `SQLException` -> `IdentityStoreException("find failed")` (`:36-43`). `keysOf`: `SELECT source, source_type, source_id FROM identity_mapping WHERE gid = ? ORDER BY source, source_type, source_id`; `source` восстанавливается `SourceSystemCode.valueOf` (неизвестное имя -> `IllegalArgumentException`, не `IdentityStoreException`) 🟢 (`:62-78`).

## Поток `approve(crosswalk)` 🟢 (`:80-101`)
1. Упорядочить: `swap = SourceKeys.compare(left, right) > 0`; `lo`, `hi`.
2. Транзакция. `link(c, lo, hi)` (`:104-118`):
   - прочитать `ga = select(lo)`, `gb = select(hi)`;
   - оба есть и различны -> `CrosswalkConflictException("keys are already mapped to different gids: <lo> and <hi>")`;
   - `gid = ga ?? gb ?? randomUUID()`;
   - `gidA = getOrCreate(lo, gid)`, `gidB = getOrCreate(hi, gid)`;
   - `gidA != gidB` (параллельный `resolve` успел между чтением и вставкой) -> тот же конфликт.
3. `INSERT INTO approved_crosswalk (...9 колонок...) ON CONFLICT DO NOTHING` — пара `(lo, hi)`, `approved_by`, `reason`, `approved_at` из `Crosswalk` (`:134-145`).
4. `commit`, возврат `gid`. Конфликт -> `rollback`, исключение проходит без обёртки; прочие `SQLException` -> `IdentityStoreException("approve failed")`.

## Альтернативы и ограничения
- Блокировок нет: корректность держится на PK `identity_mapping` и повторной проверке `gidA == gidB` 🟢. Между `SELECT` и `INSERT` другая транзакция может вставить ключ с другим `gid` -> чистый конфликт, не порча (IT `concurrentResolveAndApproveEndWithSharedGidOrCleanConflict`).
- Кросс-транзитивность: `approve(A,B)` и `approve(B,C)` дадут общий `gid` у A, B, C, если A, B уже связаны; `approve(A,C)` при существующих разных `gid` у A-B и C — конфликт, слияния нет 🟡 (следует из `link`).
- Crosswalk не обновляет граф и прочие таблицы идентичности (🟡, см. `../design.md` «Риски»).
- Оператор получает статус `APPLIED`/`CONFLICT`/`INVALID` в `CrosswalkService` (`ingestion/ingestion-service/.../CrosswalkService.java:47-58`): `CrosswalkConflictException` -> `CONFLICT`; `IllegalArgumentException`/`NullPointerException` (в т. ч. валидация `Crosswalk`) -> `INVALID`; прочее `RuntimeException` -> аудит `FAILED` и проброс 🟢. `SourceSystemCode.valueOf` вызывается внутри `try` (строки 48, 67): неизвестный `source` даёт `IllegalArgumentException` -> `INVALID` 🟢.

## Зависимости
`javax.sql.DataSource`; `SourceKey`, `SourceSystemCode` (`canonical-model`); таблицы V2.
