# candidate-matching: дизайн

> Ссылается на [`../design.md`](../design.md) (каркас транзакции, интеграция с `EventProcessor`) и [`../contracts.md`](../contracts.md) (схема V4, нормализация). Блок-схема `record`: [`../../../flowcharts/identity-resolution.md`](../../flowcharts/identity-resolution.md) (номера строк в ней устарели, см. ниже).

## Интерфейс
`PostgresIdentityCandidates(DataSource)`; `public static final int MAX_MATCHES = 50` (`IR/PostgresIdentityCandidates.java:195`).

## Поток `record(key, label, features)` 🟢 (`:239-264`)
1. `family = LabelFamily.of(label)`; `own = features` отсортированы по `kind` (порядок объявления enum: `HOSTNAME`, `REPOSITORY_URL`, `NAME`, `OWNER`), затем по `value`.
2. Транзакция. `lock("key|" + key)` — `key` это `SourceKey.toString()` (record: `SourceKey[source=..., sourceType=..., sourceId=...]`).
3. `lockValues`: для каждого **сильного** признака `lock(family.name() + "|" + kind.name() + "|" + value)` в отсортированном порядке (`:316-324`).
4. `replaceFeatures`: `DELETE FROM identity_feature WHERE source/source_type/source_id`; `INSERT` каждого признака `(…, feature=kind.name(), value, label_family=family.name(), updated_at=now)` (`:333-350`).
5. `findMatches` (`:352-401`): для каждого сильного признака `FIND_OTHERS` с параметрами `(kind, family, value, key(3), key(3), 51)`: другие ключи с тем же `feature`, `label_family`, `value`, исключая сам ключ и тех, кто делит `gid` с ним (`NOT EXISTS identity_mapping a JOIN identity_mapping b ON a.gid = b.gid`), `ORDER BY source, source_type, source_id LIMIT 51`. Если вернулось больше 50 -> WARNING и `continue` (признак пропущен целиком). Иначе `matches[other] += feature`.
6. Слабые признаки (`OWNER`): для каждого партнёра из `matches` проверка `HAS_FEATURE` (тот же `feature` и `value`); при совпадении признак добавляется в `matched` пары. Слабый признак новых пар не создаёт.
7. Для каждой пары `upsertCandidate` (`:403-427`): порядок `left/right` по `SourceKeys.compare(self, other)`, `score = FeatureKind.score(kinds)`, `INSERT identity_candidate ... ON CONFLICT (шесть ключевых колонок) DO UPDATE SET matched, score, updated_at RETURNING status, first_seen_at, updated_at`. `matched` строится в SQL из двух `text[]` через `unnest ... WITH ORDINALITY` + `jsonb_agg(jsonb_build_object('feature', f, 'value', v) ORDER BY o)`.
8. `commit`; возврат списка затронутых кандидатов (в порядке `LinkedHashMap`).

## `forget(key)` 🟢 (`:266-282`)
Транзакция: `lock("key|" + key)`, `DELETE FROM identity_feature` ключа, `commit`. `identity_candidate` не трогается.

## `candidatesOf(key)` 🟢 (`:284-314`)
`SELECT ... FROM identity_candidate WHERE (left = key) OR (right = key) ORDER BY left..., right...`; `matched` разворачивается двумя `ARRAY(SELECT e->>'feature'/'value' ... ORDER BY o)`; `FeatureKind.valueOf`, `NodeLabel.valueOf` — незнакомое имя даёт `IllegalArgumentException` (не `IdentityStoreException`) 🟢.

## Решения и ограничения
- Две блокировки (ключ, затем значения): без первой параллельные `record` одной записи упираются в PK `identity_feature`; без второй при READ COMMITTED две транзакции с одним значением не увидят друг друга и пара потеряется (`IR/PostgresIdentityCandidates.java:185-188,316`) 🟢. Слабые признаки не лочатся 🟢 (`:319-321`).
- Допущение PoC: при пропуске признака (>50) `matched` пары, найденной по другим признакам, перезаписывается без него; ранее созданный кандидат только по этому признаку остаётся нетронутым 🟡 (`:189-190,374-378`).
- Кандидат не удаляется, когда признаки перестали совпадать (запись `forget`/смена значения) 🟡; `status` не меняется 🔴 (Q-01).
- `label_family` в паре одинаков по построению (фильтр `FIND_OTHERS`); `NodeLabel.valueOf(label_family)` при чтении 🟢.
- Номера строк в `flowcharts/identity-resolution.md` для `record` (`78-102, 190-239`) относятся к другому файлу/версии: актуальные — `:239-264` (метод), `:316-427` (внутренние) 🔴 (ошибка предыдущего документа).

## Зависимости
`identity_mapping` (проверка общего `gid`, читает ту же БД), `canonical-model` (`NodeLabel`, `SourceKey`, `GraphCommand`, `UpsertNode`, `UpsertRelation`, `RelationType.OWNED_BY`, `NodeData`-подтипы). Вызывается из `EP:394-396` (`record`), `EP:271` (`forget`) 🟢.

## Риски и 🔴
Q-01 (статус кандидата), URL-нормализаторы (`../design.md`), отсутствие модульного теста на SQL (`FIND_OTHERS` покрыт только `IdentityCandidateIT`).
