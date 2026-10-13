# graph-projector / deferred-relations: требования

> Сокращения путей как в [`../requirements.md`](../requirements.md). Модульный дизайн: [`../design.md`](../design.md); дизайн сценария: [`design.md`](design.md). Решение: [`../decisions.md`](../decisions.md) D-11 (E1.14).

## Обзор

Сценарий: связь, у которой один из концов в графе ещё не известен (битая ссылка, ссылка по alias), не вызывает `RETRYING`, а сохраняется узлом `PendingRelation` и достраивается обычным `upsertRelation`, когда конечная точка спроецирована 🟢 (`GP/GraphProjector.java:62-67`, `GP/EventProcessor.java:77-79`). Нормализатор выдаёт `UnresolvedReference.relation()`, `EventProcessor` превращает его в `DeferRelation` (`GP/EventProcessor.java:221-223`); хранение — `PendingRelations`; достраивание — `healPending` и `healAliases` в транзакции проекции конечной точки. Вне сценария: правила ссылок нормализатора ([`../../normalizer/resolve-references/requirements.md`](../../normalizer/resolve-references/requirements.md)), запись связей ([`../project-request/`](../project-request/requirements.md)).

## Правила

- DR-01. `DeferRelation` не требует `gid` в запросе, но связь обязана утверждаться записью запроса (`assertedBy == key`) 🟢 (`GP/ProjectionRequest.java:84`, `GP/EventProcessor.java:581-582`; тесты `ProjectionRequestTest#deferredRelationNeedsNoGidForUnknownEndpoint`, `deferredRelationMustBeAssertedByRequestRecord`).
- DR-02. При каждой применённой (`APPLY`) проекции записи все её `PendingRelation` удаляются, затем сохраняются заново из текущих `DeferRelation`; при `SAME`/`OLD` ничего не меняется (отложенные связи записи сохраняются) 🟢 (`GP/GraphProjector.java:319-328`; IT `newReferrerVersionReplacesDeferredRelation`, `sameVersionReplayOfReferrerKeepsDeferredRelation`).
- DR-03. Tombstone записи удаляет её отложенные связи (так как `deleteOf` вызывается до команд, а tombstone не создаёт `DeferRelation`) 🟢 (`GP/GraphProjector.java:328, 338`; IT `tombstoneOfReferrerRemovesDeferredRelation`).
- DR-04. Ключ узла `PendingRelation.key` = SHA-256 (hex) от строки `len:part|` для десяти полей: `assertedBy.{source.name, sourceType, sourceId}`, `type.name`, `from.{source.name, sourceType, sourceId}`, `to.{…}`; `MERGE (p:PendingRelation {key: $key}) SET p += $props MERGE (r)-[:DEFERS]->(p)` — по одному ключу под constraint, без составного `MERGE` (дублей нет) 🟢 (`GP/PendingRelations.java:25-31, 53-80, 174-197`).
- DR-05. Свойства связи хранятся JSON-строкой `props` (`PropertyJson`); поддержаны `String`, `Boolean`, `Long`, `Integer`, конечный `Double`, `Instant`, списки; иное → `IllegalArgumentException` 🟢 (`GP/PropertyJson.java:9-20, 57-81`; `PropertyJsonTest#roundTripsAllowedValueTypes`, `rejectsMalformedAndUnsupported`).
- DR-06. Кандидаты на достраивание для конечной точки `endpoint`: `PendingRelation`, у которых `endpoint` — `from` **или** `to`, при условии, что запись-источник `r` активна (`r.active = true`) и `r.sourceVersion = p.referrerVersion` (связь, отложенная более старой версией, не достраивается); порядок `ORDER BY p.key` 🟢 (`GP/PendingRelations.java:81-106`).
- DR-07. Достраивание требует `gid` **обоих** концов из графа: для каждого конца — активная `SourceRecord` с этим ключом, утверждающая канонический узел корневой метки конца; иначе связь остаётся отложенной 🟢 (`GP/PendingRelations.java:108-134`; IT `dependencyBeforeBothServicesAppearsAfterSecondService`).
- DR-08. Достроенная связь применяется `upsertRelation` (authority, темпоральность, `assertedBy*`, идентичность) с `eventTime`, при котором она была отложена (`PendingRelation.eventTime`), и затем узел удаляется — в том числе когда `upsertRelation` пропустил её как неавторитетную (тип попадает в `skippedRelations`) 🟢 (`GP/GraphProjector.java:361-375`).
- DR-09. `healPending` вызывается после каждого `UpsertNode` для ключа записи; `healAliases` — после того же `UpsertNode`, если метка/источник подпадают под `ALIAS_RULES` и свойство-значение — строка: `REPOSITORY`/SCM → свойство `url` → `LookupKeys.repositoryUrl(url)`; `IT_SYSTEM`/EAM → свойство `eamCode` → `LookupKeys.itSystemCode(eamCode)` 🟢 (`GP/GraphProjector.java:88-95, 334-335, 377-415`).
- DR-10. Alias: конец связи с ключом-псевдонимом подменяется реальным ключом записи (`from`/`to`, совпавший с alias, → `record`); метки, свойства, validity, `assertedBy` сохраняются; если `gid` концов после подмены найти нельзя — связь остаётся 🟢 (`GP/GraphProjector.java:392-413`; IT `scmServiceBeforeEamSystemGetsDecomposedInto`).
- DR-11. Узел самого `endpoint` при достраивании не меняется 🟢 (`GP/GraphProjector.java:361-365`).
- DR-12. Повторная проекция конечной точки не дублирует ребро (`MERGE` по `(from, to, identity)`) 🟢 (IT `repeatedEndpointProjectionDoesNotDuplicateEdge`).
- DR-13. Допущение конкурентности: однопоточный диспетчер, один экземпляр, `AdminLock`; повторной проверки после записи отложенной связи нет 🟡 (`GP/GraphProjector.java:65-67`; Q-17).

## Функциональные требования

| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| DR-RF-01 | Сохранить `DeferRelation` как `PendingRelation` | Must | AC-01 |
| DR-RF-02 | Достроить связь при появлении конечной точки | Must | AC-02, AC-03 |
| DR-RF-03 | Заменять отложенные связи записи при новой версии, удалять при tombstone | Must | AC-04, AC-05 |
| DR-RF-04 | Достраивать через alias (`REPOSITORY.url`, `ITSystem.eamCode`) | Must | AC-06 |
| DR-RF-05 | Не достраивать устаревшую версию и неактивную запись | Must | AC-07 |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Консистентность | Все операции — внутри транзакции проекции, только Neo4j | `GP/PendingRelations.java:27-28`, `GP/GraphProjector.java:40-41` | 🟢 |
| Масштабируемость | Поиск кандидатов — по свойствам `PendingRelation` без индекса, кроме ключа | `GP/PendingRelations.java:88-105`, `GP/schema/Neo4jSchema.java:52-53` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01
Дано запись EAM SERVICE_DEPENDENCY ссылается на два сервиса, которых ещё нет
Когда она проецируется
Тогда в графе один PendingRelation и ребро (SourceRecord)-[:DEFERS]->(PendingRelation), ребра DEPENDS_ON нет
  (GraphProjectorIT#dependencyBeforeBothServicesAppearsAfterSecondService; ProjectionRequestTest#deferredRelationNeedsNoGidForUnknownEndpoint)

# AC-02
Дано отложена DEPENDS_ON(A, B), появился A, B ещё нет
Тогда связь остаётся отложенной (pendingCount = 1, ребер 0)
Когда появляется B
Тогда создано DEPENDS_ON с assertedBySource/assertedById записи-источника и свойствами из props, PendingRelation и DEFERS удалены (тот же IT)

# AC-03
Дано SCM SERVICE пришёл раньше EAM IT_SYSTEM и отложил DECOMPOSED_INTO по alias eamCode
Когда приходит IT_SYSTEM с этим eamCode
Тогда DECOMPOSED_INTO создана, сервис не потерян (sourceVersion записи SCM = 1)
  (scmServiceBeforeEamSystemGetsDecomposedInto; systemBeforeTeamGetsOwnedBy)

# AC-04 (замена)
Дано версия 1 записи SERVICE_DEPENDENCY отложила DEPENDS_ON(a, b), затем версия 2 отложила DEPENDS_ON(a, c)
Тогда остаётся один PendingRelation (прежний заменён); после появления a, b ребра нет, после появления c создано DEPENDS_ON(a, c)
  (newReferrerVersionReplacesDeferredRelation)

# AC-05 (негативный: tombstone)
Дано у записи есть отложенная связь
Когда запись получает tombstone
Тогда PendingRelation удалён, при появлении конечной точки ребро не создаётся (tombstoneOfReferrerRemovesDeferredRelation)

# AC-06 (негативный: идемпотентность)
Дано отложенная связь уже достроена
Когда конечная точка проецируется повторно
Тогда ребро одно, PendingRelation нет (repeatedEndpointProjectionDoesNotDuplicateEdge);
  повтор той же версии записи-источника сохраняет её PendingRelation (sameVersionReplayOfReferrerKeepsDeferredRelation)

# AC-07 (негативный: устаревшая версия)
Дано PendingRelation отложен версией 1 записи, а запись уже имеет другую версию или неактивна
Тогда он не достраивается: candidates отбирает только r.active = true AND r.sourceVersion = p.referrerVersion
  (GP/PendingRelations.java:93); при применённой проекции записи прежние PendingRelation удаляются целиком (GP/GraphProjector.java:328)

# AC-08
Дано PropertyJson с неподдерживаемым типом или битым JSON
Тогда IllegalArgumentException (PropertyJsonTest#rejectsMalformedAndUnsupported)
```

IT — `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java`; юнит — `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/PropertyJsonTest.java`, `ProjectionRequestTest.java`.

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| Хранение и достраивание | Must | Порядок прихода источников не гарантирован; без этого связи теряются или событие вечно `RETRYING` |
| Alias | Must | SCM ссылается на репозиторий/систему не ключом записи |
| Срок годности/повторная проверка | Won't | Не реализованы (Q-17, Q-18) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `GP/PendingRelations.java` | `deleteOf`, `delete`, `store`, `candidates`, `endpointGids`, `keyOf` | 🟢 (IT; юнит-тестов класса нет) |
| `GP/PropertyJson.java` | `write`, `read` | 🟢 (`PropertyJsonTest`) |
| `GP/GraphProjector.java` | `healPending`, `healAliases`, `ALIAS_RULES`, ветка `DeferRelation` | 🟢 (IT) |
| `GP/EventProcessor.java` | сборка `DeferRelation`, `resolveGids` | 🟢 (`unresolvedReferenceReachesProjectorAsDeferredRelationOnce`) |
