# graph-projector / project-request: требования

> Сокращения путей как в [`../requirements.md`](../requirements.md). Модульный дизайн: [`../design.md`](../design.md); дизайн сценария: [`design.md`](design.md); форма графа: [`../contracts.md`](../contracts.md) §1–§2.

## Обзор

Сценарий: `GraphProjector.project(ProjectionRequest)` применяет одно изменение объекта источника к Neo4j одной транзакцией `executeWrite` 🟢 (`GP/GraphProjector.java:106-111`). Вход — команды нормализатора плюс готовые `gid` и маркеры конфликтов; выход — `ProjectionResult(outcome, skippedProperties, skippedRelations)`. Вне сценария: решения `EventProcessor` ([`../process-event/`](../process-event/requirements.md)), отложенные связи как отдельная механика ([`../deferred-relations/`](../deferred-relations/requirements.md)), missing set ([`../reconcile-missing-set/`](../reconcile-missing-set/requirements.md)).

## Правила

- PR-01. `ProjectionRequest` валидирует инварианты в конструкторе, нарушение — `IllegalArgumentException`: не более одной команды `UpsertNode`/`TombstoneSourceRecord` суммарно; ключ узла/tombstone равен `key`; версия `SourceRecord` равна `version.value()`; все `UpsertRelation`/`CloseAssertion`/`DeferRelation` утверждаются `key`; `gid` есть для узла записи и обоих концов `UpsertRelation`/`CloseAssertion`; tombstone-запрос не несёт других команд; маркеры конфликтов (не `none`, не tombstone) относятся к `gid` записи 🟢 (`GP/ProjectionRequest.java:57-98`). Запись только со связями и пустой список допустимы.
- PR-02. Транзакция: `MERGE (r:SourceRecord {source, sourceType, sourceId}) SET r._lock = true REMOVE r._lock RETURN r.sourceVersion, r.active` — `MERGE` по ключу под constraint, затем фиктивная запись свойства для блокировки до чтения версии 🟢 (`GP/GraphProjector.java:304-310`).
- PR-03. Решение по версии под блокировкой: `version` в строке `null` → запись не применялась (`APPLY`); иначе `forUpsert`/`forTombstone`; `SAME` → `ProjectionResult.of(NOOP_SAME_VERSION)`, `OLD` → `of(IGNORED_OLD_VERSION)`, граф не меняется 🟢 (`GP/GraphProjector.java:311-324`).
- PR-04. При `APPLY` сначала удаляются отложенные связи записи (`PendingRelations.deleteOf`), затем команды выполняются **в порядке списка**; после них (в этом порядке): `closeReplacedRelations` (не для tombstone), `markRelationOnlyRecord` (если нет `UpsertNode`/`Tombstone`), `writeConflictMarkers`, `linkSyncRun` 🟢 (`GP/GraphProjector.java:325-358`).
- PR-05. `UpsertNode`: пишутся только авторитетные свойства (`matrix.isAuthoritative(label, name, source)`), остальные имена уходят в `skippedProperties` как `<Label>.<name>`; узел сливается по корневой метке и `gid`; `isCurrent = true` ставится при создании, а снятие `deletedAt` и `isCurrent = true` на существующем узле — только если запись MASTER для метки 🟢 (`GP/GraphProjector.java:417-465`).
- PR-06. `ASSERTS` пишется всегда (в т.ч. от неавторитетной записи): `confidence = 1.0`, `authority = MASTER` для мастера метки, иначе `SUPPLEMENTARY` 🟢 (`GP/GraphProjector.java:440-441, 461-462`).
- PR-07. `firstSeenAt` = минимум, `lastSeenAt` = максимум по `eventTime` (`CASE WHEN … IS NULL OR …`) 🟢 (`GP/GraphProjector.java:458-459`).
- PR-08. Подтип: `SET n:<Label>` для не-корневой метки, `REMOVE` меток других подтипов того же корня 🟢 (`GP/GraphProjector.java:444-447, 737-745`).
- PR-09. `UpsertRelation`: если источник не авторитетен для типа — тип в `skippedRelations`, ребро не пишется; иначе `MATCH (a:<root> {gid}), (b:<root> {gid})` + `MERGE (a)-[rel:<TYPE>{identity}]->(b)`, `SET rel += props, rel.assertedBy{Source,Type,Id}`; для темпоральных — правила открытия (BR-17); нет одного из узлов → `ProjectionException(UNRESOLVED_ENDPOINT)` 🟢 (`GP/GraphProjector.java:552-602`).
- PR-10. Идентичность связи: свойства из `RelationType.identityProperties()` (в PoC — `commit` у `DEPLOYED_FROM`) входят в шаблон `MERGE` как `{commit: $id_commit}`; другое значение — другое ребро, прежнее закрывается замещением 🟢 (`GP/GraphProjector.java:580-584, 649-660`; `CM/relation/RelationType.java`; IT `DeployedFromIT` в `platform/integration-tests`).
- PR-11. Замещение: для каждого типа с `UpsertRelation` (авторитетной, темпоральной, утверждённой этой записью) или `DeferRelation` той же записи собирается множество троек `[from.gid, to.gid, identity props в порядке имён]`; открытые связи типа с `assertedBy*` = ключ записи и тройкой вне множества получают `validTo = max(validFrom, eventTime)`. Не для tombstone 🟢 (`GP/GraphProjector.java:604-647, 662-666`).
- PR-12. `CloseAssertion` (если источник авторитетен для типа): ищет открытую связь `type` между `gid` концов с `assertedBy*` записи и ставит `validTo = max(validFrom, validTo команды)`; нет связи — no-op (идемпотентно) 🟢 (`GP/GraphProjector.java:668-685`). Команду не создаёт ни один main-код 🔴 Q-03.
- PR-13. Tombstone: `SourceRecord.sourceVersion = version`, `active = false`, `deletedAt = tombstone.deletedAt`; закрываются открытые темпоральные связи, утверждённые записью (через узлы `ASSERTS` и отдельным циклом по каждому темпоральному типу по `assertedBy*` — для записей «только связь»); узлы, утверждённые записью, при отсутствии других активных MASTER-утверждений получают `isCurrent = false`, `deletedAt = coalesce(deletedAt, at)` 🟢 (`GP/GraphProjector.java:507-550`).
- PR-14. Запись без узла (`isRelationOnly`): `sourceVersion`, `active = true`, снятие `deletedAt`, `OWNS_RECORD`; без этого дедупликация и порядок версий не работали бы 🟢 (`GP/GraphProjector.java:493-505`).
- PR-15. Маркеры конфликтов: `markers.isNone()` — ничего; иначе для `gid` на всех `ASSERTS` из узла: запись из набора → `SET a.conflicts = <имена свойств>`, остальные → `REMOVE a.conflicts` 🟢 (`GP/GraphProjector.java:471-491`).
- PR-16. `SyncRun`: при `syncRunId != null` — `MERGE (s:SyncRun {runId})`, `ON CREATE SET adapter = $source, status = 'RUNNING'`, `startedAt` = минимум, `MERGE (s)-[:PROCESSED]->(r)`; счётчики не трогаются 🟢 (`GP/GraphProjector.java:687-703`).
- PR-17. Метки и типы связей в тексте Cypher — только из `NodeLabel`/`RelationType`; значения — параметрами 🟢 (`GP/GraphProjector.java:78-79`).
- PR-18. Результат `APPLIED` несёт `skippedProperties`, `skippedRelations` (списки копируются неизменяемыми) 🟢 (`GP/GraphProjector.java:358`, `GP/ProjectionResult.java:16-20`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| PR-RF-01 | Одна транзакция, блокировка записи, решение по версии | Must | AC-01…AC-04 |
| PR-RF-02 | Запись узла с провенансом, authority, `firstSeenAt/lastSeenAt` | Must | AC-01, AC-05 |
| PR-RF-03 | Запись связей, темпоральность, идентичность, замещение | Must | AC-06…AC-08 |
| PR-RF-04 | Tombstone без физического удаления | Must | AC-09, AC-10 |
| PR-RF-05 | Валидация `ProjectionRequest` | Must | AC-11 |
| PR-RF-06 | Маркеры конфликтов, `SyncRun` | Should | AC-12 |
| PR-RF-07 | Порты чтения (`applied`, `isActive`, `activeRecords`, `DeploymentLookup`, `EamLookup`) | Must | AC-13 |
| PR-RF-08 | `CloseAssertion` | Won't (нет производителя) | `closeAssertionClosesOnlyTheOpenRelationAssertedByTheRecordAndIsIdempotent` |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Надёжность | Тело транзакции без побочных эффектов вне Neo4j (драйвер повторяет `executeWrite`) | `GP/GraphProjector.java:40-41, 108-110` | 🟢 |
| Консистентность | Параллельные проекции одного объекта сериализует блокировка `SourceRecord` | `GP/GraphProjector.java:304-310`; IT `concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion` | 🟢 |
| Масштабируемость | Закрытие связей — запрос по типу без индекса; допустимо для PoC | `GP/GraphProjector.java:528-541, 632-646` | 🟢 |
| Безопасность | Сообщения `ProjectionException` без значений источника | `GP/ProjectionException.java:3-6`, `GP/GraphProjector.java:600` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (позитивный)
Дано пустой граф и ProjectionRequest с UpsertNode ITSystem(name) версии 5, gid g
Когда project(request)
Тогда outcome = APPLIED; (SourceSystem)-[:OWNS_RECORD]->(SourceRecord{sourceVersion:"5", active:true})-[:ASSERTS{authority:"MASTER", confidence:1.0}]->(ITSystem{gid:g, isCurrent:true})
  (IT GraphProjectorIT#upsertCreatesNodeWithProvenanceAndMovesEventToProjected)

# AC-02 (повтор)
Дано тот же запрос повторяется (та же версия)
Тогда outcome = NOOP_SAME_VERSION, граф не меняется (GraphProjectorIT#replayDoesNotChangeGraph)

# AC-03 (негативный: старая версия)
Дано применена версия 184, приходит 99
Тогда outcome = IGNORED_OLD_VERSION, версия сравнивается числом (olderVersionIsIgnoredAndComparedNumerically);
  при обратном порядке доставки граф сходится к максимальной версии (outOfOrderDeliveryConvergesToTheNewestVersion)

# AC-04 (негативный: неполная запись)
Дано узел с заполненным полем, новая версия приходит без поля (null)
Тогда поле в графе не затёрто (partialRecordDoesNotEraseKnownFields)

# AC-05 (негативный: неавторитетный источник)
Дано источник не мастер для свойства
Тогда свойство не записано, имя в skippedProperties как "Label.property" (nonAuthoritativeSourceDoesNotReplaceCanonicalValue);
  для связи — тип в skippedRelations, ребра нет (nonAuthoritativeRelationIsSkippedAndReported)

# AC-06 (замещение)
Дано DEPLOYMENT RUNS_ON host A открыта; версия записи утверждает RUNS_ON host B
Тогда связь на A: validTo = eventTime, на B открыта (replacedHostClosesOldRunsOnAtEventTimeAndKeepsNewOpen); неизвестный новый конец (Deferred) закрывает старую (unknownNewHostStillClosesOldRunsOn)

# AC-07 (негативный: частичная запись)
Дано версия без утверждений типа RUNS_ON
Тогда открытые RUNS_ON не закрываются (partialRecordWithoutHostsClosesNothing); пустой список хостов — тоже ничего не закрывает (emptyHostsListClosesNothingKnownLimitation, 🟡 Q-16);
  связь, переутверждённая другой записью, не закрывается (relationReassertedByAnotherRecordIsNotClosed); повтор версии ничего не закрывает (repeatedVersionClosesNothing)

# AC-08 (негативный: нет конечного узла)
Дано UpsertRelation на узел, которого нет в графе
Тогда ProjectionException(UNRESOLVED_ENDPOINT), фантомный узел не создаётся (relationToUnknownTargetIsNotBuiltAndCreatesNoPhantomNode)

# AC-09 (tombstone)
Дано активная запись с узлом и открытыми связями
Когда приходит TombstoneSourceRecord версии не ниже применённой
Тогда SourceRecord.active=false, deletedAt задан, связи закрыты, узел isCurrent=false, deletedAt задан (tombstoneDeactivatesRecordArchivesNodeAndClosesRelations)

# AC-10 (негативный: другой мастер)
Дано узел утверждён вторым активным MASTER
Когда одна запись получает tombstone
Тогда узел остаётся isCurrent (tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains);
  upsert более новой версии реактивирует запись (upsertWithNewerVersionAfterTombstoneReactivates);
  delete раньше upsert блокирует старый upsert и не создаёт узел (deleteArrivingBeforeUpsertBlocksOlderUpsertAndCreatesNoNode)

# AC-11 (негативный: инварианты запроса)
Дано запрос с двумя UpsertNode / tombstone вместе с upsert / версией SourceRecord ≠ версии запроса / связью, утверждённой другой записью
Тогда IllegalArgumentException (ProjectionRequestTest#twoUpsertsAreRejected, tombstoneCannotBeMixedWithUpsert, recordVersionMustEqualRequestVersion, relationEndpointNeedsGidAndMustBeAssertedByRequestRecord)

# AC-12
Дано syncRunId задан и запрос повторяется
Тогда SyncRun связан ровно одним PROCESSED, счётчики не удваиваются (syncRunIsLinkedOnceAndNotDoubledOnRepeat)

# AC-13
Дано сервис SCM, на который открыта DECOMPOSED_INTO от двух активных систем EAM
Тогда eamSystemOfService пуст (eamSystemOfServiceIsEmptyWhenTwoSystemsDecomposeIt); с одной — возвращает её (eamSystemOfServiceFindsTheSingleDecomposingSystem)
```

Все `IT`-тесты — `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java`; `ProjectionRequestTest` — `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/ProjectionRequestTest.java`. **Внутри модуля `GraphProjector` против настоящего Neo4j не тестируется** — только IT платформы (требуют Docker) 🟡.

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| PR-01…PR-09, PR-13…PR-14 | Must | Весь путь записи в граф |
| PR-10, PR-11 | Must | ADR 0028, иначе устаревшие рёбра |
| PR-15, PR-16 | Should | Диагностика и провенанс прогона |
| PR-12 `CloseAssertion` | Won't | Нет производителя (Q-03) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `GP/GraphProjector.java` | `project`, `write`, `upsertNode`, `upsertRelation`, `closeReplacedRelations`, `closeAssertion`, `tombstone`, `markRelationOnlyRecord`, `writeConflictMarkers`, `linkSyncRun`, порты чтения | 🟢 (`GraphProjectorIT`, 51 `@Test`) |
| `GP/ProjectionRequest.java`, `GP/ProjectionResult.java`, `GP/ProjectionOutcome.java`, `GP/ConflictMarkers.java` | типы | 🟢 (`ProjectionRequestTest`, 12) |
| `GP/NodeProperties.java` | `of`, `value`, `utc` | 🟢 (`ProjectionRequestTest#nodePropertiesDropNullsAndConvertTypes`) |
| `GP/TemporalRelationMigration.java` | `migrate` | 🟢 (IT `temporalRelationMigrationFillsMissingValidFromAndIsIdempotent`) |
