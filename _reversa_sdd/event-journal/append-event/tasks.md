# append-event: задачи

> Общие задачи: [`../tasks.md`](../tasks.md) (T-05). Сокращения: `PEJ`.

- [ ] **T-A1** Реализовать `append` по потоку из [`design.md`](design.md): один `INSERT ... ON CONFLICT ... RETURNING`, `autoCommit=false`, commit/rollback.
  - Origin: `PEJ:53-97`
  - Критерий готовности: AC-A1, AC-A2, AC-A5 (`EventJournalIT`).
  - Уверенность: 🟢
- [ ] **T-A2** `withStatus(entry, status)` — копия `JournalEntry` с другим статусом, остальные 14 полей без изменений.
  - Origin: `PEJ:393-397`
  - Критерий готовности: повтор возвращает сохранённые `sourceVersion`, `receivedAt`, `payloadRef`.
  - Уверенность: 🟢
- [ ] **T-A3** `publish` через `append`.
  - Origin: `PEJ:99-102`
  - Критерий готовности: AC-A3.
  - Уверенность: 🟢
- [ ] **T-A4** `find` через `select`.
  - Origin: `PEJ:104-111`, `PEJ:361-370`
  - Критерий готовности: AC-A4.
  - Уверенность: 🟢

## Тесты

- [x] TT-A1 `EventJournalIT.duplicateEventIsNotInsertedTwice` (AC-A1..A3).
- [x] TT-A2 `EventJournalIT.sameEventIdFromAnotherSourceIsAccepted` (AC-A5).
- [ ] TT-A3 🟡 `publish` для нового события возвращает `true`; `find` отсутствующего — `Optional.empty()`.
- [ ] TT-A4 🟡 Откат: ошибка вставки не оставляет строку (AC-A6).
- [ ] TT-A5 🟡 `payloadRef == null` даёт `JournalEntry.payloadRef() == null` (используется в IT, но не проверяется явно).

## Порядок

T-A2 → T-A1 → T-A3/T-A4.

## Открытые 🔴

R-7 (сравнение тела дубля) — в [`../questions.md`](../questions.md); AC-A6 без теста.
