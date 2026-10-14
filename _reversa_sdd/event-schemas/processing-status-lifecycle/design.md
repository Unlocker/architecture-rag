# Use case: processing-status-lifecycle, Дизайн

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md). Таблица переходов — [design.md модуля](../design.md) §«Автомат»; диаграмма — `_reversa_sdd/flowcharts/event-schemas.md` §1, `_reversa_sdd/state-machines.md`.

## Интерфейс
`public boolean canTransitionTo(ProcessingStatus next)` — чистая функция без состояния (`ES/ProcessingStatus.java:26`) 🟢.

## Алгоритм
1. `this == next` → `true` (`:27-29`).
2. `switch (this)` (исчерпывающий, без `default`; новый enum-член не скомпилируется без ветки) → набор допустимых `next` (`:30-41`).
3. Применение: `EventJournal.transition` вызывает `canTransitionTo`; при `false` — `IllegalStateException` (`ES/EventJournal.java:31`) 🟢; в `PostgresEventJournal` под `SELECT ... FOR UPDATE` (`state-machines.md:27`) 🟡.

## Дополнительно
- Промежуточные статусы (`VALIDATED`, `NORMALIZED`, `RESOLVED`) остаются после прерванной обработки и подхватываются `JournalReader.pending` наравне с `RETRYING` (`ES/JournalReader.java:17-20`) 🟢.
- `EventProcessor` считает `PROJECTED, DUPLICATE, IGNORED_OLD_VERSION, QUARANTINED` завершёнными и повторно не обрабатывает (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:99-105,190-192`) 🟢.

## Риски
- 🟢 R-02: `SUPERSEDED` удалён (UNLOCKER-281). 🟢 R-03: операция оператора для `QUARANTINED → RETRYING` — `POST /admin/quarantine/release`.
- 🟡 Статусы продублированы в консоли и UI (`SyncSources.java:19-31`; matrix §3).
