# full-snapshot, Задачи

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Каркас — [`../tasks.md`](../tasks.md), `Poller` каркас — [`../incremental-poll/tasks.md`](../incremental-poll/tasks.md) T-01, T-02.

## Пререквизиты

- [ ] `Poller` каркас, `exclusive`, `fetch` ([`../incremental-poll/tasks.md`](../incremental-poll/tasks.md), [`../source-fetch-retry/tasks.md`](../source-fetch-retry/tasks.md)).
- [ ] `EventMapper.snapshotComplete`, `snapshotEventId`, `InboxWriter.record(marker, …)` ([`../tasks.md`](../tasks.md) T-03, T-04).

## Задачи

- [ ] T-01 `snapshotRunId`: чтение `run:<id>`, генерация и немедленное сохранение нового.
  - Origin: `AC/Poller.java:39-40, 180-188`
  - Критерий готовности: повторный вызов после сбоя возвращает тот же `runId`; после `done:` — новый
  - Уверенность: 🟢
- [ ] T-02 `snapshot`: постраничное чтение с `cursor = null`, запись событий с `correlationId = syncRunId` и `sync_run_id`, защита от отсутствия прогресса.
  - Origin: `AC/Poller.java:147-162, 176-178`
  - Критерий готовности: `PollerTest.firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects`
  - Уверенность: 🟢
- [ ] T-03 Завершение: проверка `snapshotComplete && nextCursor != null`; порядок маркер → `done:` → курсор.
  - Origin: `AC/Poller.java:164-172`
  - Критерий готовности: `PollerTest.incompleteSnapshotDoesNotWriteMarkerOrMoveCursor` (сбой источника) + TT-06 (ветка `snapshotComplete=false`); порядок вызовов journal зафиксирован
  - Уверенность: 🟢
- [ ] T-04 `snapshotOnce` и вход `pollOnce` при пустом checkpoint.
  - Origin: `AC/Poller.java:89-105`
  - Критерий готовности: `PollerTest.snapshotOnceRunsFullSnapshotWithNewRunIdEvenWhenCursorExists`
  - Уверенность: 🟢
- [ ] T-05 `SourceAdapter.startReconciliation`.
  - Origin: `AC/SourceAdapter.java:97-115`
  - Критерий готовности: первый запуск через `reconcileInterval`; повторный вызов — no-op; сбой цикла не останавливает расписание
  - Уверенность: 🟢
- [ ] T-06 `WebhookServer` контекст `/control/snapshot` и отображение исходов в HTTP.
  - Origin: `AC/WebhookServer.java:55-57, 67-94`
  - Критерий готовности: `WebhookServerControlTest.*`
  - Уверенность: 🟢

## Тестовые задачи

- [ ] TT-01 Перенос из `PollerTest`: `firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects`, `incompleteSnapshotDoesNotWriteMarkerOrMoveCursor`, `restartedSnapshotReusesRunIdAndRecordsDuplicatesInTheSameRun`, `secondSnapshotGetsNewRunIdAfterCompletion`, `snapshotOnceRunsFullSnapshotWithNewRunIdEvenWhenCursorExists`, `interruptedReconciliationSnapshotWritesNoMarkerAndResumesSameRun`. 🟢
- [ ] TT-02 Перенос `WebhookServerControlTest` (4 теста). 🟢
- [ ] TT-03 Новый: порядок вызовов журнала при завершении (маркер → `done:` → курсор) и отказ журнала на `saveCheckpoint("-snapshot", done)` — повторный запуск даёт второй маркер того же `runId` как `DUPLICATE`. 🟡
- [ ] TT-04 Новый: `objectCount` маркера при возобновлении равен числу объектов источника, а не накопленному за обе попытки. 🟡
- [ ] TT-06 Новый: последняя страница с `snapshotComplete=false` либо `nextCursor=null` → `IllegalStateException`, маркер не записан, курсоры не менялись (`AC/Poller.java:164-167`). 🟡
- [ ] TT-05 Новый: `startReconciliation` — initial delay = интервал, сбой не останавливает цикл. 🟡

## Порядок

T-01 → T-02 → T-03 → T-04 → T-06 → T-05. Тесты после T-03.

## Открытые 🔴

R-01 (литерал типа маркера в потребителях), R-02 — [`../questions.md`](../questions.md).
