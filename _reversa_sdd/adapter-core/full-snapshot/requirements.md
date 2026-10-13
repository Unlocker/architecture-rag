# full-snapshot, Полный snapshot и маркер snapshot-complete

> Часть [`adapter-core`](../requirements.md). `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`. Реализация — [`design.md`](design.md).

## Визуальная картина

Полное перечитывание источника с `cursor = null` под одним `syncRunId`. Запускается тремя путями: (1) первый `pollOnce`, когда checkpoint `<code>-poller` пуст; (2) периодическая reconciliation `SourceAdapter.startReconciliation` (по умолчанию каждые 6 ч, первый запуск через интервал); (3) вручную `POST /control/snapshot` (`Poller.snapshotOnce`) 🟢 (`AC/Poller.java:89-105`, `AC/SourceAdapter.java:97-115`, `AC/WebhookServer.java:55-57`). После последней страницы пишется маркер `architecture.sync.snapshot-complete.v1`; только по нему downstream (`Reconciler`) вычисляет missing set и ставит tombstone'ы. Сам адаптер ничего не удаляет 🟢.

## Регулярные правила

- `syncRunId` берётся из checkpoint `<code>-snapshot`, если он равен `run:<id>` (незавершённый прогон); иначе генерируется (`UUID.randomUUID()`) и сразу сохраняется как `run:<id>` 🟢 (`AC/Poller.java:180-188`). Значение `done:<id>` (завершённый прогон) или отсутствие → новый прогон.
- События snapshot: `eventId = snap:<syncRunId>:<type>/<id>/v<ver>`, `correlationId = syncRunId`, столбец `sync_run_id = syncRunId` 🟢 (`AC/Poller.java:154`, `AC/EventMapper.java:102-105`).
- Страницы читаются, пока `hasMore`; `hasMore` без нового курсора → `IllegalStateException` 🟢 (`:157-162`).
- Последняя страница обязана нести `snapshotComplete = true` **и** `nextCursor != null`; иначе `IllegalStateException("<code> ended snapshot without snapshot-complete")`, маркер **не** пишется (ловушка 9) 🟢 (`:164-167`).
- Порядок завершения строго: (1) маркер в inbox (`snapshot-complete:<runId>`, `objectCount = appended`), (2) checkpoint `<code>-snapshot = done:<runId>`, (3) checkpoint `<code>-poller = page.nextCursor()` 🟢 (`:168-171`). Исход `SNAPSHOT_COMPLETED` с `syncRunId` 🟢 (`:172`).
- `objectCount` считает записанные события прогона, **включая дубликаты** повторного чтения, и на возобновлении не накапливается между попытками: `appended` обнуляется при каждом вызове `snapshot`, а чтение всегда идёт с начала 🟢 (`:149-150`).
- Прерванный snapshot (недоступность, исключение) маркера не получает, `run:<id>` остаётся; следующий вызов переиспользует `syncRunId`, повторные события дают `DUPLICATE` и остаются в том же прогоне 🟢 (`:180-184`).
- Завершение безусловно переписывает курсор polling значением `nextCursor` последней страницы snapshot 🟡 (U-12).
- Циклы одного источника не пересекаются: `ALREADY_RUNNING` (HTTP `409` у control-входа) 🟢.

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки |
|---|---|---|---|
| RF-01 | Первый цикл без курсора — полный snapshot, заканчивающийся маркером после всех объектов, все строки с одним `syncRunId` | Must | `PollerTest.firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects` |
| RF-02 | Незавершённый snapshot (сбой источника) не пишет маркер и не двигает курсор; страница без `snapshotComplete` — `IllegalStateException` без маркера | Must | `PollerTest.incompleteSnapshotDoesNotWriteMarkerOrMoveCursor` (сбой источника); ветка `snapshotComplete=false` — теста нет 🟡 |
| RF-03 | Перезапуск snapshot переиспользует `syncRunId`; дубликаты остаются в прогоне | Must | `PollerTest.restartedSnapshotReusesRunIdAndRecordsDuplicatesInTheSameRun` |
| RF-04 | После завершения следующий snapshot получает новый `syncRunId` | Must | `PollerTest.secondSnapshotGetsNewRunIdAfterCompletion` |
| RF-05 | `snapshotOnce` работает при существующем курсоре и даёт новый `syncRunId` | Must | `PollerTest.snapshotOnceRunsFullSnapshotWithNewRunIdEvenWhenCursorExists` |
| RF-06 | Прерванный reconcile-snapshot возобновляется под тем же `syncRunId` без маркера | Must | `PollerTest.interruptedReconciliationSnapshotWritesNoMarkerAndResumesSameRun` |
| RF-07 | `POST /control/snapshot`: 202 / 409 / 503 / 500, JSON `{"outcome","appended","syncRunId"}`, GET → 405 без запуска | Should | `WebhookServerControlTest.completedSnapshotIs202WithOutcome`, `alreadyRunningIs409AndUnavailableSourceIs503`, `failureIs500WithoutDetailsAndGetIsRejectedWithoutRunning` |
| RF-08 | Без `supplier` контекст `/control/snapshot` не создаётся | Could | `WebhookServerControlTest.noControlContextWithoutSnapshotSupplier` |
| RF-09 | Периодическая reconciliation: первый запуск через `reconcileInterval`, общий замок с polling | Should | прямого теста нет 🟡 |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Надёжность | Удаления возможны только после маркера (ловушка 9); без подтверждённой полноты маркер не ставится | `AC/Poller.java:164-169` | 🟢 |
| Надёжность | Прерванный прогон не теряет `syncRunId` (хранится в checkpoint) | `AC/Poller.java:180-188` | 🟢 |
| Безопасность | `/control/snapshot` не аутентифицирован; сеть — единственная граница | `AC/WebhookServer.java:18-22` | 🟢 |
| Доступность | Запрос control выполняется синхронно в потоке запроса (виртуальный) — клиент ждёт конца snapshot | `AC/WebhookServer.java:22, 53, 77` | 🟢 |

## Критерии приёмки

```gherkin
# Позитив: первый запуск
Дано пустой checkpoint "eam-poller" и источник из 4 объектов на нескольких страницах
Когда pollOnce()
Тогда записаны 4 события snap:<runId>:… и затем маркер snapshot-complete:<runId> с objectCount=4
  И все строки журнала имеют sync_run_id = runId
  И checkpoint "eam-snapshot" = "done:<runId>", "eam-poller" = nextCursor последней страницы
  И результат SNAPSHOT_COMPLETED, appended = 4, syncRunId = runId     # PollerTest.firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects

# Негатив: источник упал посреди snapshot
Дано страница 1 записана, дальше источник недоступен дольше, чем терпит RetryPolicy
Когда pollOnce()
Тогда GAVE_UP, маркер не записан, checkpoint "eam-poller" не создан   # PollerTest.incompleteSnapshotDoesNotWriteMarkerOrMoveCursor

# Негатив: источник не подтвердил полноту
Дано последняя страница с hasMore=false, snapshotComplete=false (или nextCursor=null)
Когда snapshot()
Тогда IllegalStateException, маркер не записан, курсор polling не изменён   # 🟡 прямого теста нет (TT-06); код AC/Poller.java:164-167

# Позитив: возобновление
Дано snapshot прерван на середине (checkpoint "eam-snapshot" = "run:R")
Когда вызывается snapshotOnce() снова
Тогда syncRunId = R, повторные события — DUPLICATE, в конце один маркер snapshot-complete:R
                                                                      # PollerTest.restartedSnapshotReusesRunIdAndRecordsDuplicatesInTheSameRun

# Позитив: новый прогон после завершения
Дано checkpoint "eam-snapshot" = "done:R"
Когда snapshotOnce()
Тогда syncRunId ≠ R                                                    # PollerTest.secondSnapshotGetsNewRunIdAfterCompletion

# Негатив: control при занятом замке
Дано идёт цикл Poller
Когда POST /control/snapshot
Тогда 409 и тело с outcome=ALREADY_RUNNING                             # WebhookServerControlTest.alreadyRunningIs409AndUnavailableSourceIs503

# Негатив: источник недоступен
Дано fetchChanges исчерпал попытки
Когда POST /control/snapshot
Тогда 503, outcome=GAVE_UP                                             # WebhookServerControlTest.alreadyRunningIs409AndUnavailableSourceIs503

# Негатив: сбой в snapshot
Дано snapshot бросает RuntimeException
Когда POST /control/snapshot
Тогда 500, тело "{}", деталей нет                                     # WebhookServerControlTest.failureIs500WithoutDetailsAndGetIsRejectedWithoutRunning
```

## Приоритет

Must: RF-01…RF-06 (корректность удалений и идемпотентность). Should: RF-07, RF-09. Could: RF-08.

## Трассировка

| Файл | Символ | Тест | Покрытие |
|---|---|---|---|
| `AC/Poller.java:98-105, 147-188` | `snapshotOnce`, `snapshot`, `snapshotRunId` | `PollerTest` (6 тестов выше) | 🟢 |
| `AC/EventMapper.java:80-95, 102-105` | `snapshotComplete`, `snapshotEventId` | `PollerTest.firstCycle…` | 🟢 |
| `AC/InboxWriter.java:40-57` | `record(marker, …)`, `markerRawJson` | `StoredEventReaderContractTest` (ingestion-service) | 🟢 |
| `AC/WebhookServer.java:67-94` | `control` | `WebhookServerControlTest` (4) | 🟢 |
| `AC/SourceAdapter.java:97-115` | `startReconciliation` | — | 🟡 |
