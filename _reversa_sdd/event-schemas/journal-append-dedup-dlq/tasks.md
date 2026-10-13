# Use case: journal-append-dedup-dlq, Задачи

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

- [ ] T-01 Интерфейс `EventJournal` (8 операций) и DTO `JournalEntry`, `Checkpoint`, `SnapshotContents`, `ObjectRef`.
  - Origin: `ES/EventJournal.java:13-61`, `ES/JournalEntry.java:14-29`, `ES/Checkpoint.java:13`, `ES/SnapshotContents.java:14-18`, `ES/ObjectRef.java:7`
  - Критерий готовности: сигнатуры совпадают, Javadoc-гарантии перенесены.
  - Уверенность: 🟢
- [ ] T-02 `CanonicalEventPublisher` и `RawPayloadRef`/`RawPayloadStore`.
  - Origin: `ES/CanonicalEventPublisher.java:7-15`, `ES/RawPayloadStore.java:4-11`
  - Критерий готовности: сигнатуры совпадают.
  - Уверенность: 🟢
- [ ] TT-01 Контрактный набор AC-01…AC-08, общий для всех реализаций `EventJournal`.
  - Уверенность: 🟡 (в модуле отсутствует, TT-12 модуля)

Порядок: T-01 → T-02 → TT-01.

## Открытые 🔴
R-03, R-04 (Q-01, Q-03).
