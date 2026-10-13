# read-journal: задачи

> Общие задачи: [`../tasks.md`](../tasks.md) (T-07, T-08). Сокращения: `PEJ`.

- [ ] **T-R1** `read(JournalQuery)`: динамический `WHERE` из фиксированных фрагментов; параметры по порядку; `ORDER BY received_at, source, event_id LIMIT ?`.
  - Origin: `PEJ:162-207`
  - Критерий готовности: AC-R1, AC-R2.
  - Уверенность: 🟢
- [ ] **T-R2** `pending(after, retryNotAfter, limit)`: проверка `limit`; условие статусов; keyset.
  - Origin: `PEJ:209-237`
  - Критерий готовности: AC-R3, AC-R4.
  - Уверенность: 🟢
- [ ] **T-R3** `loadCheckpoint`/`saveCheckpoint` (upsert).
  - Origin: `PEJ:239-272`
  - Критерий готовности: AC-R5.
  - Уверенность: 🟢
- [ ] **T-R4** `snapshotContents` (`starts_with`, счётчик, минимальный `received_at`).
  - Origin: `PEJ:274-299`
  - Критерий готовности: AC-R6, AC-R7.
  - Уверенность: 🟢
- [ ] **T-R5** `objectsReceivedSince` (`DISTINCT`, `>=`).
  - Origin: `PEJ:301-318`
  - Критерий готовности: AC-R6.
  - Уверенность: 🟢
- [ ] **T-R6** `map`/`StoredEvent` (добавляет `type`, `subject`).
  - Origin: `PEJ:372-391`, `PEJ:200`
  - Критерий готовности: `StoredEvent.type()`/`subject()` совпадают с вставленными (проверено `readerPagesByKeysetInReceivedOrderAndFilters`).
  - Уверенность: 🟢

## Тесты

- [x] TT-R1 `EventJournalIT.readerPagesByKeysetInReceivedOrderAndFilters` (AC-R1, AC-R2).
- [x] TT-R2 `EventJournalIT.readerRejectsBadLimit` (AC-R2).
- [x] TT-R3 `EventJournalIT.pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset` (AC-R3, AC-R4).
- [x] TT-R4 `EventJournalIT.checkpointSurvivesJournalRestart` (AC-R5).
- [ ] TT-R5 🟡 `snapshotContents` на PG, включая `%`/`_` (AC-R6, AC-R7).
- [ ] TT-R6 🟡 `objectsReceivedSince` на PG (граница `>=`).
- [ ] TT-R7 🟡 `pending` с `retryNotAfter == null` (определить контракт).

## Порядок

T-R6 → T-R1 → T-R2 → T-R3 → T-R4/T-R5.

## Открытые 🔴

Нет собственных 🔴; 🟡 по индексу и памяти — в [`../design.md`](../design.md). R-4 (лимит попыток) влияет на повторную выдачу `RETRYING` в `pending`: [`../questions.md`](../questions.md).
