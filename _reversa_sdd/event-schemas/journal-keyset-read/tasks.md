# Use case: journal-keyset-read, Задачи

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

- [ ] T-01 `JournalKey` (NPE на `null`, `of`).
  - Origin: `ES/JournalKey.java:11-22`
  - Критерий готовности: `JournalKey.of(entry)` = `(receivedAt, source, eventId)`.
  - Уверенность: 🟢
- [ ] T-02 `JournalQuery` с `REPLAY_PREFIX`, `MAX_LIMIT`, проверками и `after`.
  - Origin: `ES/JournalQuery.java:18-39`
  - Критерий готовности: AC-01, AC-02; `after` не меняет прочие поля.
  - Уверенность: 🟢
- [ ] T-03 `JournalReader` (`read`, `pending`) и `StoredEvent`.
  - Origin: `ES/JournalReader.java:12-29`, `ES/StoredEvent.java:12-17`
  - Критерий готовности: сигнатуры и Javadoc перенесены.
  - Уверенность: 🟢
- [ ] TT-01 Unit-тесты `JournalQuery`/`JournalKey` (модуль, AC-01, AC-02); контрактные AC-03…AC-05 — в реализации.
  - Уверенность: 🟡 (в коде отсутствуют)

Порядок: T-01 → T-02 → T-03 → TT-01.

## Открытые 🔴
Нет специфичных; общие R-04 (префикс `replay:` — единственный в контракте).
