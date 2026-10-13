# graph-projector / project-request: дизайн

> Сокращения путей как в [`../requirements.md`](../requirements.md). Требования: [`requirements.md`](requirements.md); модульный дизайн: [`../design.md`](../design.md); форма графа и схема: [`../contracts.md`](../contracts.md).

## Интерфейс

| Символ | Сигнатура | Результат |
|---|---|---|
| `GraphProjector(Driver, AuthorityMatrix)` | конструктор; `requireNonNull` обоих | (`GP/GraphProjector.java:101-104`) 🟢 |
| `project` | `(ProjectionRequest)` → `ProjectionResult` | `try (Session s = driver.session()) { return s.executeWrite(tx -> write(tx, request)); }` (`GP/GraphProjector.java:106-111`) 🟢 |
| `write` | `private (TransactionContext, ProjectionRequest)` | тело транзакции (`GP/GraphProjector.java:302-359`) 🟢 |

`ProjectionOutcome`: `APPLIED`, `NOOP_SAME_VERSION`, `IGNORED_OLD_VERSION` (`GP/ProjectionOutcome.java:4-11`). Драйвер — внедряемый единственный bean; `AuthorityMatrix` — `AuthorityMatrix.defaults()` из `ingestion-service` (`IS/IngestionServiceConfiguration.java:85-86`).

## Поток `write` (по шагам)

1. **Блокировка.** `MERGE (r:SourceRecord {source: $source, sourceType: $sourceType, sourceId: $sourceId}) SET r._lock = true REMOVE r._lock RETURN r.sourceVersion AS version, r.active AS active` → `single()` (`GP/GraphProjector.java:305-310`). `applied = version == null ? null : AppliedRecord(SourceVersion(version), active == true)` (`GP/GraphProjector.java:311, 718-722`).
2. **Решение.** tombstone → `forTombstone(version, applied?.version, applied != null && applied.active)`; иначе `forUpsert(version, applied?.version)`. Не `APPLY` → `ProjectionResult.of(SAME ? NOOP_SAME_VERSION : IGNORED_OLD_VERSION)` (`GP/GraphProjector.java:312-324`).
3. **Очистка отложенных.** `PendingRelations.deleteOf(tx, key)` (`GP/GraphProjector.java:327-328`).
4. **Команды по порядку списка** (`switch` по sealed `GraphCommand`, `GP/GraphProjector.java:329-349`):

| Команда | Действие |
|---|---|
| `UpsertNode` | `upsertNode` → `healPending(tx, record.key, skippedRelations)` → `healAliases(tx, upsert, skippedRelations)` |
| `DeferRelation` | `PendingRelations.store(tx, key, request.version().value(), request.eventTime(), defer.relation())` |
| `TombstoneSourceRecord` | `tombstone(tx, request, tombstone)` |
| `UpsertRelation` | `upsertRelation(tx, relation, gids[from], gids[to], eventTime, skippedRelations)` |
| `CloseAssertion` | `closeAssertion(tx, request, close)` |

5. **Хвост.** `!isTombstone` → `closeReplacedRelations`; `isRelationOnly` → `markRelationOnlyRecord`; `writeConflictMarkers(request.conflicts())`; `linkSyncRun`; результат `new ProjectionResult(APPLIED, skippedProperties, skippedRelations)` (`GP/GraphProjector.java:350-358`).

Порядок команд определяется нормализатором (узел записи — первым, если он есть); проектор порядок не меняет. Связи, зависящие от узлов этого же запроса, поэтому видят уже записанный узел 🟡 (`GP/GraphProjector.java:329-349`).

## `upsertNode` (подробно)

Параметры: ключ записи, `version = record.sourceVersion`, `hash = record.contentHash`, `fetchedAt = utc(record.fetchedAt)`, `at = utc(eventTime)`, `gid = gids[key].toString()`, `props` (авторитетные, без `null`), `master = matrix.isAuthoritative(label, source)`, `authority = master ? "MASTER" : "SUPPLEMENTARY"` (`GP/GraphProjector.java:417-441`). Cypher (метки подставляются из enum, остальное — `$`-параметры) 🟢 (`GP/GraphProjector.java:447-464`):

```cypher
MATCH (r:SourceRecord {source: $source, sourceType: $sourceType, sourceId: $sourceId})
SET r.sourceVersion = $version, r.contentHash = $hash, r.fetchedAt = $fetchedAt, r.active = true
REMOVE r.deletedAt
MERGE (sys:SourceSystem {code: $source})
MERGE (sys)-[:OWNS_RECORD]->(r)
MERGE (n:<RootLabel> {gid: $gid})
ON CREATE SET n.isCurrent = true
SET n += $props
[SET n:<SubtypeLabel>] [REMOVE n:<OtherSubtypeOfSameRoot>...]
SET n.firstSeenAt = CASE WHEN n.firstSeenAt IS NULL OR n.firstSeenAt > $at THEN $at ELSE n.firstSeenAt END,
    n.lastSeenAt  = CASE WHEN n.lastSeenAt  IS NULL OR n.lastSeenAt  < $at THEN $at ELSE n.lastSeenAt  END
FOREACH (x IN CASE WHEN $master THEN [1] ELSE [] END | SET n.isCurrent = true REMOVE n.deletedAt)
MERGE (r)-[a:ASSERTS]->(n)
SET a.confidence = 1.0, a.authority = $authority
```

Свойства `props` собираются так: `NodeProperties.of(data)` (компоненты record'а через рефлексию, `null` отброшены, `Instant` → UTC `OffsetDateTime`, `Enum` → `name()`, списки рекурсивно) → фильтр `matrix.isAuthoritative(label, name, source)`; отфильтрованное → `skippedProperties.add(label.label() + "." + name)` (`GP/GraphProjector.java:424-432`, `GP/NodeProperties.java:25-55`). Корневая метка: `root(label)` поднимается по `supertype()`; `ComputeInstance` — корень для `VirtualMachine`/`PhysicalServer` (`GP/GraphProjector.java:729-735`).

## `upsertRelation` (подробно)

1. Нет авторитетности `matrix.isAuthoritative(type, assertedBy.source)` → `skipped.add(type.name())`, возврат (`GP/GraphProjector.java:559-563`).
2. Параметры: ключ `assertedBy`, `from`/`to` (строки `gid`), `at`, `props = NodeProperties.of(relation.properties())`; для темпорального типа — `validFrom`, `validTo` из `Validity` (оба `null` при `validity == null`) (`GP/GraphProjector.java:564-570`).
3. Шаблон: `MATCH (a:<rootsFrom> {gid:$from}), (b:<rootsTo> {gid:$to}) MERGE (a)-[rel:<TYPE> {<id-props>}]->(b)`; id-свойства — `$id_<name>` из `NodeProperties.value(relation.properties().get(name))`; для темпорального типа `WITH rel, (rel.validFrom IS NULL OR rel.validTo IS NOT NULL) AS opening`; затем `SET rel += $props, rel.assertedBySource = $source, rel.assertedByType = $sourceType, rel.assertedById = $sourceId` и, для темпорального, `, rel.validFrom = CASE WHEN $validFrom IS NOT NULL THEN $validFrom WHEN opening THEN $at ELSE rel.validFrom END, rel.validTo = $validTo`; `RETURN 1 AS ok` (`GP/GraphProjector.java:571-598`).
4. Пустой результат (не найден один из узлов) → `ProjectionException(UNRESOLVED_ENDPOINT, "relation endpoint is not in the graph")` (`GP/GraphProjector.java:599-601`).

Следствие 🟡: `validFrom` из источника перезаписывает существующий при каждом утверждении; без него у открытой связи `validFrom` сохраняется (`ELSE rel.validFrom`), у новой или закрытой — `eventTime`.

## `closeReplacedRelations` (подробно)

Для каждой команды: `UpsertRelation r` при `replaces(r, key)` (`type.temporal()` ∧ `assertedBy == key` ∧ источник авторитетен) → тройка `[gid(from), gid(to), identity props в порядке sortedIdentity]` в `kept[type]`; `DeferRelation d` при `replaces(d.relation, key)` → только заводит `kept[type]` пустым; прочее игнорируется (`GP/GraphProjector.java:611-629, 662-666`). Для каждого типа из `kept` (тип подставляется из enum):

```cypher
MATCH (a)-[rel:<TYPE>]->(b)
WHERE rel.validTo IS NULL AND rel.assertedBySource = $source AND rel.assertedByType = $sourceType AND rel.assertedById = $sourceId
  AND NOT [a.gid, b.gid, rel.<idprop>...] IN $kept
SET rel.validTo = CASE WHEN rel.validFrom > $at THEN rel.validFrom ELSE $at END
```

(`GP/GraphProjector.java:633-646`). Следствия: тип без команд в версии не затрагивается (частичная запись ничего не закрывает); пустой список хостов не порождает команд — старые связи остаются (тест-ограничение, Q-16); `validTo` не бывает раньше `validFrom`.

## `tombstone` (подробно)

Четыре запроса (`GP/GraphProjector.java:507-550`):

1. `MATCH (r:SourceRecord …) SET r.sourceVersion = $version, r.active = false, r.deletedAt = $at MERGE (sys:SourceSystem {code}) MERGE (sys)-[:OWNS_RECORD]->(r)`.
2. Закрытие временных связей узлов, которые утверждает запись: `MATCH (r)-[:ASSERTS]->(n)-[rel]-() WHERE type(rel) IN $temporalTypes AND rel.validTo IS NULL AND rel.assertedBy* = запись SET rel.validTo = CASE WHEN rel.validFrom > $at THEN rel.validFrom ELSE $at END`; `$temporalTypes` — имена `RelationType` с `temporal()` (`GP/GraphProjector.java:747-749`).
3. Для записей «только связь» (нет `ASSERTS`): цикл по `RelationType.values()` с `temporal()`: `MATCH ()-[rel:<TYPE>]->() WHERE rel.validTo IS NULL AND rel.assertedBy* = запись SET rel.validTo = …` (тип — из enum).
4. Архивация узла: `MATCH (r)-[:ASSERTS]->(n) WHERE NOT EXISTS { MATCH (o:SourceRecord {active: true})-[:ASSERTS {authority: 'MASTER'}]->(n) WHERE o <> r } SET n.isCurrent = false, n.deletedAt = coalesce(n.deletedAt, $at)`.

`$at = tombstone.deletedAt` (= `event.time()` либо `now` из `Reconciler`) 🟢. Физического удаления и снятия `ASSERTS` нет; `PendingRelation` записи удалены на шаге 3 `write`.

## `closeAssertion`

Если источник не авторитетен для типа — молча `return` (в `skippedRelations` не попадает) 🟡; иначе `MATCH (a:<корневые метки `RelationType.sources()`> {gid:$from})-[rel:<TYPE>]->(b:<корневые метки `RelationType.targets()`> {gid:$to}) WHERE rel.assertedBy* = запись AND rel.validTo IS NULL SET rel.validTo = CASE WHEN rel.validFrom > $validTo THEN rel.validFrom ELSE $validTo END`; нет связи — ничего не меняется (`GP/GraphProjector.java:668-685`). Мёртвый для production путь: Q-03.

## `writeConflictMarkers`, `markRelationOnlyRecord`, `linkSyncRun`

- `writeConflictMarkers`: `records` = список `{source, sourceType, sourceId, properties}` из `markers.byRecord()`; корневые метки `roots` = все корни `NodeLabel.canonical()` через `|` (отсортированы); `MATCH (n:<roots> {gid: $gid})<-[a:ASSERTS]-(r:SourceRecord)` → для рёбер записей из набора `SET a.conflicts = hit[0].properties`, иначе `REMOVE a.conflicts` (`GP/GraphProjector.java:471-491`).
- `markRelationOnlyRecord`: `SET r.sourceVersion = $version, r.active = true REMOVE r.deletedAt` + `SourceSystem`/`OWNS_RECORD` (`GP/GraphProjector.java:493-505`).
- `linkSyncRun`: только при `syncRunId != null`; `MERGE (s:SyncRun {runId})`, `ON CREATE SET s.adapter = $source, s.status = 'RUNNING'`, `startedAt` — минимум по `at`, `MERGE (s)-[:PROCESSED]->(r)` (`GP/GraphProjector.java:687-703`).

## Порты чтения

Все — `session.executeRead` в `try-with-resources`, значения параметрами (`GP/GraphProjector.java:113-300`):

| Метод | Условие | Особенность |
|---|---|---|
| `applied` | `r.sourceVersion IS NOT NULL` | `active` как boolean |
| `isActive` | `r.active = true`, `count(r) > 0` | для `ReferenceResolver` нормализатора |
| `activeRecords` | `{source}`, `active = true`, версия задана | весь источник в память (комментарий `GP/GraphProjector.java:146-147`) |
| `repositoryByUrl` | SCM `REPOSITORY`, `Repository.url = $url` | `ORDER BY sourceId LIMIT 1` |
| `itSystemByEamCode` | EAM `IT_SYSTEM`, `ITSystem.eamCode = $code` | `ORDER BY toInteger(sourceId)`, наименьший |
| `servicesImplementedIn` | SCM `SERVICE` с открытой `IMPLEMENTED_IN` | `DISTINCT … ORDER BY sourceId` |
| `eamSystemOfService` | EAM `IT_SYSTEM` с открытой `DECOMPOSED_INTO` на сервис | >1 системы → WARNING и `Optional.empty()` |
| `solutionPlatform` | EAM `SOLUTION`, `Solution.platformId` | `LIMIT 1` |
| `systemsOfSolution` | EAM `IT_SYSTEM` с открытой `USES_SOLUTION` | `DISTINCT … ORDER BY sourceId` |
| `chartDeploymentsOfService` | DEPLOYMAP, открытая `HAS_DEPLOYMENT`, `d.eamSystemId IS NOT NULL` | `ORDER BY sourceType, sourceId` |

## Зависимости

`AuthorityMatrix` ([`../../canonical-model/authority-lookup/design.md`](../../canonical-model/authority-lookup/design.md)), `NodeLabel`, `RelationType`, `SourceKey`, `LookupKeys`, `Validity`, `SourceVersion`, neo4j-java-driver 5.x (`ingestion/graph-projector/pom.xml:35-38`). Отложенные связи и alias — [`../deferred-relations/design.md`](../deferred-relations/design.md).

## Риски и пробелы

🔴 [`../questions.md`](../questions.md) Q-03 (`CloseAssertion`), Q-04 (`SyncRun`); 🟡 Q-09 (пустой узел от неавторитетной записи), Q-13 (tombstone с применённой версией; tombstone неизвестной записи), Q-14 (`EPOCH`), Q-16 (ограничения замещения), Q-17 (допущение одного писателя). Сам по себе `GraphProjector` в модуле юнит-тестами не покрыт — только IT с Docker 🟡.
