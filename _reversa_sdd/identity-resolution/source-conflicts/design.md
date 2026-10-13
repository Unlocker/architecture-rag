# source-conflicts: дизайн

> Ссылается на [`../design.md`](../design.md) и [`../contracts.md`](../contracts.md) (V5/V7). Блок-схемы и стейт-диаграмма: [`../../../flowcharts/identity-resolution.md`](../../flowcharts/identity-resolution.md); автомат: `_reversa_sdd/state-machines.md` §4.

## Интерфейс
`PostgresSourceConflicts(DataSource, AuthorityMatrix)` (оба `requireNonNull`). Транзакционный помощник `inTransaction(failure, Work)`: lock/работа/`commit`; `SQLException` -> `IdentityStoreException(failure, e)` (`IR/PostgresSourceConflicts.java:142-160`).

## Поток `assertProperties(key, gid, label, values)` 🟢 (`:81-103`)
1. Транзакция; `lock("conflict|" + gid)` (`pg_advisory_xact_lock(hashtextextended(?, 0))`).
2. `DELETE FROM property_assertion` записи `key`.
3. Для каждой пары `(property, value)` из `values`: `INSERT property_assertion (source, source_type, source_id, property, gid, label=label.name(), value, authoritative, asserted_at)`, `authoritative = matrix.isAuthoritative(label, property, key.source())`. `null` значения вызывающий не передаёт (`EP:403-411` собирает `String.valueOf` для всех свойств `NodeProperties.of`).
4. `recompute(c, gid)`; `commit`.

## `recompute(c, gid)` 🟢 (`:210-271`)
1. `DIVERGING` (`:43-47`): для каждого неавторитетного утверждения `d` по `gid` найти `m` — авторитетное утверждение того же `gid` и `property`, **первое** по `(source, source_type, source_id)` (`LATERAL ... LIMIT 1`); пары, где `d.value <> m.value`.
2. Для каждой расходящейся пары — `UPSERT_CONFLICT` с `kind='PROPERTY'`, `now` в `opened_at` и `updated_at`. `ON CONFLICT (gid, property, dissent_source, dissent_type, dissent_id) DO UPDATE`: обновляет значения и мастера, `kind`, `status='OPEN'`, `resolved_at=NULL`; `updated_at` остаётся прежним, если строка уже `OPEN` с теми же `dissent_value` и `master_value`, иначе `now`; `opened_at` не обновляется (`:48-58`).
3. `OPEN_KEYS` (`status='OPEN' AND kind='PROPERTY'`) за вычетом ещё расходящихся — `RESOLVE`: `status='RESOLVED'`, `resolved_at = updated_at = now`.
4. Результат — `selectOpen(gid)`: все открытые строки обоих видов.

## `assertRelation` 🟢 (`:105-119`)
Lock `conflict|<gid>`; `master.isEmpty() || dissentValue.equals(masterValue)` -> `resolveRelation(gid, dissent, property)` (`UPDATE ... SET status='RESOLVED', resolved_at, updated_at WHERE gid AND kind='RELATION' AND status='OPEN' AND dissent_* AND property`); иначе `upsertRelation` (тот же `UPSERT_CONFLICT`, `kind='RELATION'`, мастер из параметра). Возврат `selectOpen`. Пересчёт `PROPERTY` не вызывается. Строка закрывается только если открыта (`status='OPEN'` в условии), поэтому повтор не двигает `resolved_at` 🟢.

## `retract(key, gid)` 🟢 (`:121-131`)
Lock; `DELETE` утверждений записи; `resolveRelation(gid, key, null)` (все `RELATION`-строки, где она `dissent`); `recompute`. Если `key` был мастером свойства — авторитетных утверждений нет, `DIVERGING` пуст, конфликты свойства закрываются (IT `retractOfDissentResolvesAndRetractOfMasterResolvesToo`) 🟢. `RELATION`-строки, где запись была `master`, не закрываются (ключ `master_*` в `RESOLVE_RELATION` не используется) 🟡.

## Состояния `source_conflict`
`OPEN -> OPEN` (значения изменились, `updated_at` меняется), `OPEN -> RESOLVED` (перестали расходиться, retract, нет мастера для `RELATION`), `RESOLVED -> OPEN` (расхождение вернулось). Строки не удаляются 🟢.

## Решения и риски
- Сравнение по утверждениям всех записей `gid` (не по графу) делает результат независимым от порядка событий 🟢 (`IR/SourceConflicts.java:13-15`).
- `IR/PostgresSourceConflicts.java:30-31`: при replay/rebuild промежуточные события могут видеть «будущие» утверждения других записей; итог после последнего события совпадает с исходным 🟢 (заявлено Javadoc'ом, прямого теста нет 🟡).
- `authoritative` фиксируется при записи; смена матрицы не пересчитывается 🟡 (`:96`).
- `property_assertion.gid` фиксируется при записи; после crosswalk сохраняет прежний `gid` до следующей записи 🟡.
- В шаге 2 `recompute` каждая пара — отдельный `PreparedStatement` (N+1 к БД на gid); число свойств мало 🟡 (`:226-246`).
- Ошибки предыдущих документов: `state-machines.md` ссылается на `PostgresSourceConflicts.java:50-57` для UPSERT, актуально `:48-58` 🔴.

## Зависимости
`AuthorityMatrix.isAuthoritative(NodeLabel, String, SourceSystemCode)` (`contracts/canonical-model/.../authority/AuthorityMatrix.java:53-55`: правило свойства или, если его нет, правило узла); значения свойств строит `EventProcessor` (`EP:403-411`); для `RELATION` — `EP:448,476,508,553` 🟢.
