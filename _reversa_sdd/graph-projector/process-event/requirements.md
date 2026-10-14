# graph-projector / process-event: требования

> Сокращения путей: `GP`, `GPT`, `IT`, `ES` — как в [`../requirements.md`](../requirements.md). Модульный дизайн: [`../design.md`](../design.md); дизайн сценария: [`design.md`](design.md). Метки 🟢/🟡/🔴.

## Обзор

Сценарий: событие журнала (`RECEIVED` или `RETRYING`) доводится до конечного статуса вызовом `EventProcessor.process(event, raw)` 🟢 (`GP/EventProcessor.java:185-199`). Входит: маршрут по `type`, решение по версии до записи, нормализация, получение `gid`, кандидаты и сверки конфликтов в PG, вызов проектора, перевод статусов, checkpoint, перевод ошибок в `RETRYING`/`QUARANTINED`. Вне сценария: транзакция Neo4j ([`../project-request/`](../project-request/requirements.md)), missing set ([`../reconcile-missing-set/`](../reconcile-missing-set/requirements.md)), выбор событий диспетчером (`ingestion-service`).

## Правила

- PE-01. Событие обязано быть в журнале; иначе `IllegalArgumentException`, состояние не меняется 🟢 (`GP/EventProcessor.java:186-189`).
- PE-02. Для статуса из `{PROJECTED, DUPLICATE, IGNORED_OLD_VERSION, QUARANTINED}` возвращается `ProcessingResult(status, errorCode, null)` без каких-либо вызовов 🟢 (`GP/EventProcessor.java:99-105, 190-192`).
- PE-03. Маршрут: `architecture.asset.upserted.v1` → `upsert`; `architecture.asset.deleted.v1` → `delete`; `architecture.sync.snapshot-complete.v1` → `snapshotComplete`; иное → `QUARANTINED`/`UNSUPPORTED_EVENT_TYPE` до нормализатора 🟢 (`GP/EventProcessor.java:193-198`).
- PE-04. `upsert`/`delete` с неизвестным `source` → `QUARANTINED`/`UNKNOWN_SOURCE`; `delete` с `dataschema ≠ urn:corp:schema:asset-deleted:1` → `QUARANTINED`/`UNKNOWN_SCHEMA_VERSION` 🟢 (`GP/EventProcessor.java:203-205, 256-261`).
- PE-05. Только для статуса `RECEIVED` до нормализации: версия равна применённой → `DUPLICATE`; ниже → `IGNORED_OLD_VERSION`; оба с checkpoint, граф не трогается 🟢 (`GP/EventProcessor.java:303-322`).
- PE-06. Для `RETRYING` (и любого не-`RECEIVED`) решение принимается позже, перед записью в PG: отсекается только `OLD`; равная версия идёт дальше (проектор вернёт `NOOP_SAME_VERSION`, итоговый статус — `PROJECTED`) 🟢 (`GP/EventProcessor.java:324-344, 383-387`).
- PE-07. Цепочка `upsert`: `→VALIDATED` → `normalize` → (`Quarantined` → карантин с кодом нормализатора) → `warnings.record` (если список непуст) → `DeferRelation` на каждую различную `UnresolvedReference.relation()` → `→NORMALIZED` → `resolveGids` → `recordCandidates` → `ignoreIfOlderOnRetry` → `assertProperties` → `assertSystemRelation` → `assertSystemPlatform` → `→RESOLVED` → `write` 🟢 (`GP/EventProcessor.java:212-251`).
- PE-08. Связь с двумя неизвестными концами приходит дважды (по ссылке на каждый конец) — откладывается один раз (`distinct()`) 🟢 (`GP/EventProcessor.java:222-223`; тест `unresolvedReferenceReachesProjectorAsDeferredRelationOnce`).
- PE-09. `gid` для ключа события выдаёт `identity.resolve(key)`; концы `UpsertRelation` — `identity.find`; без `gid` → `RETRYING`/`UNRESOLVED_ENDPOINT`; для `DeferRelation` `gid` не требуется 🟢 (`GP/EventProcessor.java:569-601`).
- PE-10. `delete`: `→VALIDATED`, `→NORMALIZED`, `candidates.forget(key)`, `ignoreIfOlderOnRetry(tombstone)`, `retractProperties(key)`, `→RESOLVED`, запись `ProjectionRequest` с единственной командой `TombstoneSourceRecord(key, event.time())` и пустой картой `gids` 🟢 (`GP/EventProcessor.java:268-286`).
- PE-11. `snapshot-complete`: сначала проверка `objectCount` (нет валидного → `QUARANTINED`/`SNAPSHOT_OBJECT_COUNT_MISSING`, UNLOCKER-279); статусы до `RESOLVED`, вызов `ReconciliationTrigger.snapshotComplete(source, data.sourceId, event.id, objectCount)`, затем `PROJECTED` + checkpoint; `SnapshotRejectedException` → `QUARANTINED` с её кодом; прочее `RuntimeException` → `RETRYING`/`RECONCILIATION_TRIGGER_FAILED` с причиной `getClass().getSimpleName()` 🟢 (`GP/EventProcessor.java:289-300`).
- PE-12. После записи (`APPLIED` или `NOOP_SAME_VERSION`) для SCM-`SERVICE` перепроверяются его развёртывания, для EAM-`SOLUTION` — её системы; для `IGNORED_OLD_VERSION` и tombstone — нет 🟢 (`GP/EventProcessor.java:374-382, 461-464, 534-538`).
- PE-13. Checkpoint `(projector, source, eventId)` сохраняется в `finish` после перехода в конечный статус (в т.ч. `DUPLICATE`, `IGNORED_OLD_VERSION`); `retry`/`quarantine` его не сдвигают 🟢 (`GP/EventProcessor.java:612-627`).
- PE-14. Сообщения/причины не содержат значений источника; неожиданные исключения (NPE нормализатора, ошибка журнала, `IllegalStateException` перехода) пробрасываются вызывающему, статус остаётся достигнутым, повтор безопасен 🟢 (`GP/EventProcessor.java:72-74`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| PE-RF-01 | Полный путь `upserted` до `PROJECTED` с checkpoint | Must | AC-01 |
| PE-RF-02 | Отсечение `DUPLICATE`/`IGNORED_OLD_VERSION` до записи | Must | AC-02, AC-03 |
| PE-RF-03 | Перевод ошибок в `RETRYING`/`QUARANTINED` по таблице | Must | AC-04, AC-05 |
| PE-RF-04 | `delete` → tombstone без нормализатора | Must | AC-06, AC-07 |
| PE-RF-05 | Маркер `snapshot-complete` → `ReconciliationTrigger` | Must | AC-08 |
| PE-RF-06 | Кандидаты и конфликты в PG до графа; `RETRYING` при сбое хранилища | Should | AC-09 |
| PE-RF-07 | Сверки `eamSystemId`/`platformId` и их перепроверка | Should | AC-10 |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Надёжность | Повтор события безопасен на каждом шаге: запись кандидатов и утверждений идемпотентна, граф — по версии | `GP/EventProcessor.java:234, 247, 326-327, 379` | 🟢 |
| Надёжность | Нет лимита попыток `RETRYING`; эскалации в карантин по числу попыток нет | `GP/EventProcessor.java:619-622` (только переход) | 🟡 (Q-11) |
| Безопасность | Недоверенные тексты источника не попадают в причины и логи | `GP/EventProcessor.java:72-74, 235, 248, 364, 372` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (позитивный)
Дано событие asset.upserted.v1 в статусе RECEIVED, граф пуст
Когда вызывается process(event, raw)
Тогда статусы проходят VALIDATED, NORMALIZED, RESOLVED, PROJECTED по одному шагу, проектору передан ProjectionRequest с gid ключа,
  checkpoint (projector, source, eventId) сохранён после записи
  (EventProcessorTest#upsertWalksStatusesStepByStepAndSavesCheckpointAfterWrite)

# AC-02 (идемпотентность, негативный по записи)
Дано в графе применена запись версии 5, событие с другим eventId и версией 5 в статусе RECEIVED
Когда вызывается process
Тогда статус DUPLICATE, project не вызван, checkpoint сохранён
  (sameVersionUnderAnotherEventIdIsDuplicateAndDoesNotTouchGraph)

# AC-03
Дано применена версия 184, приходит событие версии 99
Тогда IGNORED_OLD_VERSION до нормализации
  (olderVersionIsIgnoredBeforeWrite; числовое сравнение — VersionDecisionTest#versionsAreComparedAsNumbersNotStrings)

# AC-04 (негативный: граф недоступен)
Дано project бросает ServiceUnavailableException
Тогда RETRYING, errorCode GRAPH_UNAVAILABLE (причина — имя класса, не текст исключения), checkpoint не сдвинут
  (graphOutageMovesEventToRetryingWithoutCheckpoint)

# AC-05 (негативный: отказ по запросу)
Дано project бросает ClientException с кодом Neo.ClientError.Statement.SyntaxError
Тогда QUARANTINED/GRAPH_REJECTED; а для кода нарушения constraint или безопасности — RETRYING/GRAPH_CLIENT_ERROR
  (securityAndConstraintErrorsRetryButStatementErrorsQuarantine)

# AC-06
Дано событие asset.deleted.v1 с dataschema urn:corp:schema:asset-deleted:1
Тогда normalizer не вызывается, проектору уходит ProjectionRequest с одним TombstoneSourceRecord и пустыми gids
  (deleteBecomesTombstoneRequestWithoutNormalizer)

# AC-07 (негативный)
Дано asset.deleted.v1 с другим dataschema
Тогда QUARANTINED/UNKNOWN_SCHEMA_VERSION (deleteWithUnknownSchemaIsQuarantined); с версией ниже применённой — IGNORED_OLD_VERSION (deleteWithOldVersionIsIgnored)

# AC-08
Дано snapshot-complete.v1
Тогда ReconciliationTrigger получает (source, data.sourceId, event.id, objectCount), граф не пишется, статус PROJECTED
  (snapshotCompleteIsHandedToReconciliationAndNotProjected; IT GraphProjectorIT#snapshotCompleteIsHandedToReconciliationAndNotProjected)

# AC-09 (негативный: сбой хранилища PG)
Дано candidates.record бросает IdentityStoreException
Тогда RETRYING/IDENTITY_CANDIDATE_FAILED, проектор не вызван, checkpoint не сдвинут
  (candidateStoreFailureRetriesBeforeGraphIsTouched, conflictStoreFailureRetriesWithoutTouchingGraphOrCheckpoint)

# AC-10
Дано Deployment DEPLOYMAP с eamSystemId и сервис SCM, чья система в графе известна
Тогда assertRelation вызван с chartValue и master из lookup, маркеры конфликта переданы проектору
  (deploymentWithChartSystemIsComparedWithSystemOfItsService)

# AC-11 (негативный: неизвестный тип)
Дано type вне трёх известных
Тогда QUARANTINED/UNSUPPORTED_EVENT_TYPE, normalizer не вызван (unknownTypeIsQuarantinedBeforeNormalizer)

# AC-12 (негативный: не в журнале)
Дано событие, которого нет в журнале
Тогда IllegalArgumentException (eventMissingFromJournalIsRejected)
```

Все `EventProcessorTest#…` — `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/EventProcessorTest.java`; IT — `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java`.

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| Статусная цепочка, версия, ошибки, checkpoint | Must | Вызывается для каждого события |
| `delete` и `snapshot-complete` | Must | Единственные пути удаления |
| Кандидаты и конфликты | Should | Диагностика, граф не меняют |
| Сверки `eamSystemId`/`platformId` | Should | ADR 0030/0031 |
| Выход из `QUARANTINED` из самого `process` | Won't | Выход — операция оператора `POST /admin/quarantine/release` (UNLOCKER-281); `SUPERSEDED` удалён (Q-01) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `GP/EventProcessor.java` | `process`, `upsert`, `delete`, `snapshotComplete`, `decideBeforeWrite`, `ignoreIfOlderOnRetry`, `write`, `resolveGids`, `advance`, `finish`, `retry`, `quarantine` | 🟢 (`EventProcessorTest`, 46 `@Test`) |
| `GP/VersionDecision.java` | `forUpsert`, `forTombstone` | 🟢 (`VersionDecisionTest`) |
| `GP/ProcessingResult.java`, `GP/NormalizationWarnings.java` | результат, порт предупреждений | 🟢 |
