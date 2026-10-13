# graph-projector / reconcile-missing-set: требования

> Сокращения путей как в [`../requirements.md`](../requirements.md). Модульный дизайн: [`../design.md`](../design.md); дизайн сценария: [`design.md`](design.md). ADR [0020](../../../docs/adr/0020-tombstone-instead-of-hard-delete.md); ловушка №9 `CLAUDE.md`.

## Обзор

Сценарий: после маркера `architecture.sync.snapshot-complete.v1` активные записи источника, которых полный snapshot не отдал (missing set), получают tombstone 🟢 (`GP/Reconciler.java:27-31`). Маркер приходит в `EventProcessor.snapshotComplete`, тот вызывает `ReconciliationTrigger.snapshotComplete` → `Reconciler.reconcile` (`GP/EventProcessor.java:289-300`, `GP/Reconciler.java:88-91`). Без маркера удалений нет: падение на середине snapshot ничего не удаляет. Вне сценария: формирование snapshot и маркера на стороне адаптера ([`../../adapter-core/full-snapshot/requirements.md`](../../adapter-core/full-snapshot/requirements.md)), `POST /admin/reconcile/{source}` (`ingestion-service`, запускает новый snapshot адаптера), replay.

## Правила

- RC-01. Источник маркера должен быть известен (`EventProcessor.sourceCode`), иначе `IllegalArgumentException("event source is not known")` 🟢 (`GP/Reconciler.java:101-104`; тест `ReconcilerTest#unknownSourceIsRejected`). В `EventProcessor` маркер этой проверки не проходит и исключение превращается в `RETRYING` 🟡 (Q-11).
- RC-02. Множество объектов прогона берётся из **журнала** (`journal.snapshotContents(source, syncRunId)`: события с `event_id`, начинающимся с `snap:<syncRunId>:`), а не из графа: событие объекта могло закончиться `DUPLICATE`, `QUARANTINED` или `RETRYING`, но объект в источнике есть 🟢 (`GP/Reconciler.java:35-37, 105`; `ingestion/event-journal/.../PostgresEventJournal.java:275-299`; IT `quarantinedSnapshotObjectIsNotTreatedAsMissing`).
- RC-12. 🟢 [UNLOCKER-279, вопросы 6 и 15] Защита от неполного snapshot, все проверки до первой записи (отказ ничего не закрывает частично):
  1. `EventProcessor` без валидного `objectCount` в payload маркера (нет поля, не целое, отрицательное) не вызывает `Reconciler`: маркер → `QUARANTINED`, код `SNAPSHOT_OBJECT_COUNT_MISSING`. Явный `0` допустим. Через диспетчер/replay такой маркер не доходит и раньше: `StoredEventReader` отвергает raw без `objectCount`.
  2. Прогон с полнотой `PARTIAL` (маркер несёт `completeness`; адаптер ставит `PARTIAL`, если среди записей прогона была `PARTIAL`; отсутствие поля = `COMPLETE`) удалений не делает: missing set не считается, пишется WARN и метрика `archrag.ingestion.reconcile.skipped{source,reason=PARTIAL}`, маркер завершается `PROJECTED`.
  3. Порог массового закрытия: если missing set (за вычетом объектов прогона и недавно тронутых) больше `maxMissingShare` активных записей источника (`archrag.reconcile.max-missing-share`, по умолчанию 0.5, диапазон `(0, 1]`), `Reconciler` бросает `SnapshotRejectedException(SNAPSHOT_MASS_CLOSE_LIMIT)`, маркер → `QUARANTINED` (метрика `reconcile.skipped{reason=SNAPSHOT_MASS_CLOSE_LIMIT}`). Равенство границе допустимо. Выход из карантина — replay оператором; ручного подтверждения в PoC нет (стадия 2). Источник, у которого закрыта единственная запись из одной, тоже отклоняется (100% > доли).
  4. Tombstone `Reconciler` вызывает `IdentityCandidates.forget(key)` до записи в граф, как `EventProcessor.delete`; для записи, у которой применена версия новее, не вызывается; сбой хранилища не глушится.
  Тесты: `ReconcilerTest` (`partialRunDeletesNothingAndIsReported`, `missingSetOverTheShareIsRejectedBeforeAnyWrite`, `missingSetEqualToTheShareIsAllowed`, `tombstoneForgetsIdentityFeaturesBeforeTheGraphWrite`, …), `EventProcessorTest` (`snapshotCompleteWithoutObjectCountIsQuarantinedAndReconciliationIsNotCalled`, …), IT `ReconciliationIT` (`markerWithoutObjectCountIsQuarantinedAndClosesNothing`, `partialSnapshotClosesNothing`, `massCloseOverTheShareQuarantinesTheMarkerAndClosesNothing`, `reconcileForgetsIdentityFeaturesOfClosedRecordsOnly`).
- RC-13. 🟢 [UNLOCKER-279, П-18] Воскрешение той же версией не поддерживается и согласуется с К-1: `VersionDecision.forUpsert` с версией, равной применённой версии tombstone'нутой записи, даёт `SAME` → `DUPLICATE`; вернуть объект можно только версией выше. Tombstone той же версии к ещё активной записи применяется (`forTombstone`). Поведение не менялось, зафиксировано здесь.
- RC-03. Если событий прогона в журнале меньше `objectCount` маркера — `IllegalStateException("snapshot run is incomplete in the journal")`, ничего не удаляется; маркер уходит в `RETRYING` 🟢 (`GP/Reconciler.java:106-108`; `incompleteRunInJournalDeletesNothing`; IT `failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun`).
- RC-04. Начало окна «недавно тронутых» = `run.firstReceivedAt`, а для пустого прогона — `received_at` маркера; `touched = journal.objectsReceivedSince(source, since)` (`received_at >= since`, любые типы событий источника) 🟢 (`GP/Reconciler.java:109-113`; `emptyRunWithMarkerTombstonesEverythingActiveBeforeTheMarker`).
- RC-05. Для каждой активной записи источника (`projection.activeRecords(code)`): объект в прогоне → оставить (`keptInSnapshot`); не в прогоне, но в `touched` → оставить (`keptRecentlyTouched`; webhook мог создать его после чтения snapshot, судьбу решит следующий прогон); иначе → tombstone 🟢 (`GP/Reconciler.java:119-141`; `objectTouchedAfterRunStartIsNotDeleted`; IT `objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun`).
- RC-06. Tombstone идёт с **уже применённой версией** записи: если объект успели обновить, проекция вернёт `IGNORED_OLD_VERSION` и не затрёт новое состояние 🟢 (`GP/Reconciler.java:42-44, 130-135`; `tombstonesOnlyActiveRecordsMissingFromTheRun`; `projectionOldVersionIsCountedAndNotTreatedAsDeleted`).
- RC-07. До записи в граф tombstone фиксируется в журнале событием `architecture.asset.deleted.v1` (`eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>`) и raw в `RawPayloadStore`; повтор находит существующую строку и не создаёт дубль, доводя её до конечного статуса 🟢 (`GP/Reconciler.java:45-48, 156-194`; `tombstoneIsJournaledBeforeProjectionAndReachesFinalStatus`, `repeatedReconcileDoesNotDuplicateTheJournalRowAndOldVersionIsRecorded`; IT `reprocessingTheSameMarkerChangesNothing`).
- RC-08. Строка tombstone проходит статусы `VALIDATED → NORMALIZED → RESOLVED`, затем `PROJECTED` (исход `APPLIED` и `NOOP_SAME_VERSION`) либо `IGNORED_OLD_VERSION`; для уже финальной строки `finishTombstone` не вызывает `transition` 🟢 (`GP/Reconciler.java:175-194`).
- RC-09. Утверждения свойств tombstone'нутой записи снимаются в `SourceConflicts.retract` **до** записи в граф (как в `EventProcessor.delete`); `ConflictMarkers` идут в том же `ProjectionRequest`; для записи без `gid` или с уже применённой более новой версией маркеры не меняются; сбой хранилища не глушится — граф не тронут, запись остаётся в `activeRecords`, следующий `snapshot-complete` повторит tombstone 🟢 (`GP/Reconciler.java:49-55, 131, 146-154`; `retractsAssertionsBeforeProjectionAndPassesMarkers`, `recordWithoutGidDoesNotCallConflictStore`, `conflictStoreFailurePropagatesBeforeGraphIsTouched`, `newerAppliedVersionSkipsRetract`).
- RC-10. `Report(tombstoned, ignoredOldVersion, keptRecentlyTouched, keptInSnapshot)`: `tombstoned` — исход `APPLIED`; `ignoredOldVersion` — `IGNORED_OLD_VERSION`; исход `NOOP_SAME_VERSION` не учитывается ни в одном счётчике 🟢 (`GP/Reconciler.java:86, 137-143`). `EventProcessor` отчёт отбрасывает 🟡.
- RC-11. Остаточная гонка (событие применилось между проверкой и `retract`) принята: следующее upsert-событие записи целиком восстановит утверждения 🟡 (`GP/Reconciler.java:53-55`).
- RC-12. Hard delete отсутствует: tombstone — это `active=false` + закрытие связей + архивация узла (см. [`../project-request/design.md`](../project-request/design.md) «tombstone») 🟢.

## Функциональные требования

| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| RC-RF-01 | Удалять только после маркера и только по полному прогону | Must | AC-01, AC-02 |
| RC-RF-02 | Не удалять объекты, присутствующие в прогоне или тронутые после его начала | Must | AC-03, AC-04 |
| RC-RF-03 | Фиксировать tombstone в журнале и S3 до графа; повтор идемпотентен | Must | AC-05, AC-06 |
| RC-RF-04 | Не затирать более новое состояние (применённая версия) | Must | AC-07 |
| RC-RF-05 | Снимать утверждения конфликтов до графа | Should | AC-08 |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Масштабируемость | `activeRecords` читает весь источник в память; допустимо для PoC | `GP/GraphProjector.java:146-147` | 🟢 |
| Надёжность | Сбой на любой записи прерывает reconcile; уже выполненные tombstone остаются, оставшиеся повторяются следующим маркером | `GP/Reconciler.java:49-55, 119-142` | 🟢 |
| Безопасность | Сообщения исключений без значений источника | `GP/Reconciler.java:103, 107` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (позитивный)
Дано в графе активны team/kept (v3) и team/gone (v7), прогон run-1 содержит только team/kept, маркер objectCount = 1
Когда reconcile("urn:corp:…", "run-1", "snapshot-complete:run-1", 1)
Тогда Report(tombstoned=1, ignoredOldVersion=0, keptRecentlyTouched=0, keptInSnapshot=1);
  проектору ушёл один ProjectionRequest ключа team/gone, версия 7, единственная команда TombstoneSourceRecord, eventTime = now
  (ReconcilerTest#tombstonesOnlyActiveRecordsMissingFromTheRun; IT ReconciliationIT#missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive)

# AC-02 (негативный: неполный прогон)
Дано маркер заявляет objectCount = 3, в журнале прогона 1 событие
Тогда IllegalStateException, ни одного project, маркер получает RETRYING;
  после дозагрузки прогона с тем же syncRunId повторный маркер удаляет пропавший объект
  (incompleteRunInJournalDeletesNothing; IT failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun)

# AC-03 (негативный: объект тронут во время snapshot)
Дано активный объект не в прогоне, но по нему после начала прогона пришло событие (webhook)
Тогда он не удаляется, keptRecentlyTouched = 1 (objectTouchedAfterRunStartIsNotDeleted; IT objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun)

# AC-04 (негативный: карантин объекта прогона)
Дано событие объекта прогона закончилось QUARANTINED
Тогда активная запись объекта не считается пропавшей (IT quarantinedSnapshotObjectIsNotTreatedAsMissing)

# AC-05
Дано tombstone для team/gone
Тогда в журнале строка reconcile:run-1:TEAM/gone типа architecture.asset.deleted.v1, dataschema urn:corp:schema:asset-deleted:1, time = now, sourceVersion 7;
  raw содержит "operation":"DELETE", "sourceVersion":7, "updatedAt":"<now>"; переходы ->VALIDATED, ->NORMALIZED, ->RESOLVED, ->PROJECTED
  (tombstoneIsJournaledBeforeProjectionAndReachesFinalStatus)

# AC-06 (идемпотентность)
Дано reconcile вызван дважды с тем же syncRunId
Тогда журнал и S3 содержат по одной записи, второй вызов ничего не меняет в графе
  (repeatedReconcileDoesNotDuplicateTheJournalRowAndOldVersionIsRecorded; IT reprocessingTheSameMarkerChangesNothing)

# AC-07 (негативный: запись успели обновить)
Дано проектор возвращает IGNORED_OLD_VERSION
Тогда Report(0, 1, 0, 0), строка tombstone получает IGNORED_OLD_VERSION, объект не считается удалённым
  (projectionOldVersionIsCountedAndNotTreatedAsDeleted)

# AC-08
Дано запись с gid
Тогда conflicts.retract вызван до project, маркеры переданы в ProjectionRequest; для записи без gid retract не вызывается; при более новой применённой версии retract пропускается; сбой retract прерывает reconcile до записи в граф
  (retractsAssertionsBeforeProjectionAndPassesMarkers, recordWithoutGidDoesNotCallConflictStore, newerAppliedVersionSkipsRetract, conflictStoreFailurePropagatesBeforeGraphIsTouched)

# AC-09 (негативный: неизвестный источник)
Дано source = "urn:corp:nope"
Тогда IllegalArgumentException (unknownSourceIsRejected)

# AC-10 (пустой источник)
Дано прогон без событий и маркер с objectCount = 0
Тогда все активные записи источника получают tombstone, окно touched начинается с received_at маркера
  (emptyRunWithMarkerTombstonesEverythingActiveBeforeTheMarker)
```

`ReconcilerTest` — `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/ReconcilerTest.java` (12 `@Test`); IT — `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java`.

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| RC-01…RC-07 | Must | Единственный путь удалений; ловушка №9 |
| RC-09 (конфликты) | Should | Согласованность PG с `EventProcessor.delete` |
| Проверка `dataschema` маркера | Won't сейчас | Не реализована (Q-05); обязательность `objectCount` реализована в UNLOCKER-279 (RC-12) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `GP/Reconciler.java` | `reconcile`, `retract`, `journalTombstone`, `finishTombstone`, `rawDelete`, `quote` | 🟢 (`ReconcilerTest`, `ReconciliationIT`) |
| `GP/ReconciliationTrigger.java` | порт | 🟢 |
| `GP/EventProcessor.java` | `snapshotComplete`, `objectCount`, `sourceCode` | 🟢 (`snapshotCompleteIsHandedToReconciliationAndNotProjected`) |
