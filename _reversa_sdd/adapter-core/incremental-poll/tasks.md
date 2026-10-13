# incremental-poll, Задачи

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Общий каркас — [`../tasks.md`](../tasks.md).

## Пререквизиты

- [ ] `EventMapper`, `InboxWriter`, `RetryPolicy`/`HttpSourceConnector` ([`../tasks.md`](../tasks.md) T-01…T-04; [`source-fetch-retry`](../source-fetch-retry/tasks.md)).

## Задачи

- [ ] T-01 `Poller` каркас: поля, конструктор (8 параметров), `create(...)` (`Clock.systemUTC()`, `Sleeper.THREAD`, `new Random()`, UUID), `pollerConsumer()`, `snapshotConsumer()`.
  - Origin: `AC/Poller.java:37-86`
  - Критерий готовности: имена consumer `<code>-poller` / `<code>-snapshot`
  - Уверенность: 🟢
- [ ] T-02 `exclusive`: `tryLock`, `ALREADY_RUNNING`, перевод `GaveUp` в `GAVE_UP`, `unlock` в `finally`.
  - Origin: `AC/Poller.java:107-118, 215-224`
  - Критерий готовности: `PollerTest.concurrentCycleOfTheSameSourceIsRejected`
  - Уверенность: 🟢
- [ ] T-03 `pollOnce` + `incremental`: цикл страниц, запись до сдвига курсора, защита от отсутствия прогресса, исходы `ADVANCED`/`UP_TO_DATE`.
  - Origin: `AC/Poller.java:89-96, 120-145, 176-178`
  - Критерий готовности: `PollerTest.incrementalPollingAdvancesCursorAfterEachPage`, `hasMoreWithoutNewCursorFailsInsteadOfLooping`, `journalFailureDoesNotMoveCheckpoint`
  - Уверенность: 🟢
- [ ] T-04 `pause`: прерывание восстанавливает флаг и бросает `IllegalStateException`.
  - Origin: `AC/Poller.java:206-213`
  - Критерий готовности: прямого теста нет (🟡)
  - Уверенность: 🟢
- [ ] T-05 `SourceAdapter.startPolling`: `scheduleWithFixedDelay(…, 0, pollInterval)`, логирование.
  - Origin: `AC/SourceAdapter.java:72-90`
  - Критерий готовности: см. [`../tasks.md`](../tasks.md) TT-09
  - Уверенность: 🟢

## Тестовые задачи

- [ ] TT-01 Перенос 8 тестов из `PollerTest` по списку RF-01…RF-08 ([`requirements.md`](requirements.md)): `incrementalPollingAdvancesCursorAfterEachPage`, `journalFailureDoesNotMoveCheckpoint`, `hasMoreWithoutNewCursorFailsInsteadOfLooping`, `redeliveredAndReorderedPageProducesNoDuplicateRows`, `rateLimitKeepsCursorAndResumesFromTheSamePlace`, `transientFailureIsRetriedWithinOneCycle`, `deleteBecomesTombstoneEventWithEmptyPayload`, `partialRecordWithNullsDoesNotCrashAndKeepsNullsOutOfPayload`. 🟢
- [ ] TT-02 Новый: пустая страница и неизменный курсор → `UP_TO_DATE` без записи checkpoint. 🟡
- [ ] TT-03 Новый: страница с изменениями, но без `nextCursor` → фиксирует поведение U-01 (`UP_TO_DATE`, `appended > 0`). 🟡
- [ ] TT-04 Новый: `GAVE_UP` в середине (вторая страница недоступна): первая страница записана, курсор первой сохранён, `appended` в результате = числу записанных. 🟡

## Порядок

T-01 → T-02 → T-03 → T-04 → T-05; тесты — после T-03.

## Открытые 🔴

- R-03 (отравленный объект), R-02 (формулировка ADR 0020) — [`../questions.md`](../questions.md).
