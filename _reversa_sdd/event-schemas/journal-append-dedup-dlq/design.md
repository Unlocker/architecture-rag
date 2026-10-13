# Use case: journal-append-dedup-dlq, Дизайн

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md). Сигнатуры портов — [design.md модуля](../design.md) §«Порты». Блок-схема — `_reversa_sdd/flowcharts/event-schemas.md` §2 и §5.

## Потоки
**append** (`ES/EventJournal.java:22`): ключ `(source, event.id())` есть? нет → вставить `RECEIVED` и вернуть `JournalEntry`; да → вернуть прежнюю запись со статусом `DUPLICATE`, строку не менять 🟢. Из события журнал сохраняет `correlationId`, `sourceType`, `sourceId`, `sourceVersion`, `schemaVersion`, `type`, `subject` (последние два читаются через `StoredEvent`), из аргументов — `payloadRef`, `syncRunId` (`ES/JournalEntry.java:14-29`, `ES/StoredEvent.java:12`) 🟡.

**transition** (`:33`): нет события → IAE; `!current.canTransitionTo(status)` → ISE; иначе обновить `status`, `errorCode`, `errorReason` 🟢 (поведение `updatedAt`/`attempts` задаёт реализация 🟡).

**toDlq** (`:41`): `errorCode` пуст или события нет → IAE; статус → `QUARANTINED`; необработанная запись DLQ есть → не создавать вторую 🟢.

**checkpoint** (`:44,50`): `consumer` — имя потребителя (например `projector`), `cursor` непрозрачен; порядок записи обеспечивает вызывающий (`EventProcessor.finish`: после коммита Neo4j и перехода в конечный статус; `docs/02-data-model.md` §5.3) 🟢.

**snapshot/reconcile** (`:57,60`): основа missing set: объекты прогона берутся из журнала, т. к. проекция могла не состояться; `firstReceivedAt` — `null`, если событий нет (`ES/SnapshotContents.java:12`) 🟢.

## Зависимости
- Реализация: `ingestion/event-journal` (`PostgresEventJournal`; таблицы `inbox_event`, `dlq_entry`, `consumer_checkpoint`) — [`database/`](../../database/erd.md).
- Потребители: `adapter-core` (`InboxWriter`), `graph-projector` (`EventProcessor`, `Reconciler`), `ingestion-service` (`ReplayService`, `JournalDispatcher`).

## Риски
- 🟡 Идемпотентность `toDlq` и атомарность `append` — только Javadoc; контрактного теста для всех реализаций нет (TT-12 модуля).
- 🔴 R-03: карантин без штатного выхода. 🔴 R-04: формат id `snap:`/`reconcile:` не в контракте.
