# transition-event: задачи

> Общие задачи: [`../tasks.md`](../tasks.md) (T-06). Сокращения: `PEJ`, `ES`.

- [ ] **T-T1** `update`: `SELECT ... FOR UPDATE`, проверка существования и `canTransitionTo`, `UPDATE ... RETURNING`.
  - Origin: `PEJ:320-354`
  - Критерий готовности: AC-T1..AC-T4.
  - Уверенность: 🟢
- [ ] **T-T2** `transition`: транзакция вокруг `update`, rollback при ошибке, обёртка `SQLException` в `JournalException("transition failed")`.
  - Origin: `PEJ:113-129`
  - Критерий готовности: ошибка перехода не оставляет изменений (AC-T3).
  - Уверенность: 🟢
- [ ] **T-T3** `toDlq`: проверка `errorCode`; `update(QUARANTINED)` + идемпотентная вставка в `dlq_entry` в одной транзакции.
  - Origin: `PEJ:131-160`
  - Критерий готовности: AC-T5..AC-T7.
  - Уверенность: 🟢
- [ ] **T-T4** `truncate` (500) и `fingerprint` (12 hex SHA-256).
  - Origin: `PEJ:356-359`, `PEJ:399-402`
  - Критерий готовности: AC-T8; сообщения исключений не содержат `source`/`eventId` целиком.
  - Уверенность: 🟢
- [ ] **T-T5** Таблица переходов — в `contracts/event-schemas` (`ProcessingStatus.canTransitionTo`), здесь не дублировать.
  - Origin: `ES/ProcessingStatus.java:26-42`
  - Критерий готовности: журнал вызывает `canTransitionTo`, а не собственные правила.
  - Уверенность: 🟢

## Тесты

- [x] TT-T1 `EventJournalIT.transitionsAndDlqArePersisted` (AC-T1, AC-T4, AC-T5).
- [x] TT-T2 `EventJournalIT.repeatedDlqKeepsSingleRecord` (AC-T6).
- [x] TT-T3 `EventJournalIT.dlqRejectsBlankCodeAndUnknownEvent` (AC-T7).
- [x] TT-T4 `EventJournalIT.finalStateCannotBeRolledBack` (AC-T3).
- [ ] TT-T5 🟡 `error_*` обнуляются при переходе без ошибки; `attempts` не растёт на не-`RETRYING` (AC-T2).
- [ ] TT-T6 🟡 Усечение причины до 500 (AC-T8).
- [ ] TT-T7 🟡 Откат перевода в `QUARANTINED` при сбое вставки DLQ.
- [ ] TT-T8 🟡 Конкурентные `transition` одного события сериализуются (две сессии).

## Порядок

T-T4 → T-T1 → T-T2 → T-T3.

## Открытые 🔴

R-1 (`replayed_at`), R-2 (выход из карантина), R-3 (`SUPERSEDED`), R-4 (лимит попыток): [`../questions.md`](../questions.md). Закрыты UNLOCKER-281.
