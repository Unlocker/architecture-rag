# append-event: дизайн

> Общий дизайн: [`../design.md`](../design.md). Сокращения: `PEJ`, `SQL`, `ES`.

## Интерфейс

`JournalEntry append(CanonicalEvent event, RawPayloadRef payloadRef /*nullable*/, String syncRunId /*nullable*/)` (`PEJ:53-54`); `boolean publish(...)` (`PEJ:99-102`); `Optional<JournalEntry> find(String source, String eventId)` (`PEJ:104-111`).

Используемые поля события: `source()`, `id()`, `type()`, `subject()`, `correlationid()`, `dataschema()`, `data().sourceType()`, `data().sourceId()`, `data().sourceVersion().value()` (`PEJ:64-73`). Контракт `CanonicalEvent` — `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEvent.java`.

## Основной поток

1. `now = Instant.now()` (`PEJ:55`).
2. Открыть соединение, `autoCommit=false` (`PEJ:60-61`).
3. Выполнить `INSERT INTO inbox_event (source, event_id, type, subject, source_type, source_id, source_version, correlation_id, sync_run_id, schema_version, status, attempts, payload_key, payload_hash, received_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?) ON CONFLICT (source, event_id) DO NOTHING RETURNING <COLUMNS>`; порядок параметров: source, id, type, subject, sourceType, sourceId, sourceVersion, correlationid, syncRunId, dataschema, `'RECEIVED'`, payload key, payload hash, now, now (`PEJ:56-78`).
4. Если `RETURNING` вернул строку → `map`, `commit`, вернуть `JournalEntry` (`PEJ:79-84`).
5. Иначе (конфликт) → `select(c, source, id)`; нет строки → `JournalException("inbox row vanished after conflict", null)`; есть → `commit`, вернуть `withStatus(existing, DUPLICATE)` (`PEJ:86-89`).

`publish`: `append(...).status() != DUPLICATE` (`PEJ:101`).

## Альтернативные потоки

- `SQLException`/`RuntimeException` в транзакции → `rollback()` и повторный `throw`; внешний `catch (SQLException)` оборачивает в `JournalException("append failed", e)`; `RuntimeException` (включая «row vanished») выходит без оборачивания 🟢 (`PEJ:90-96`).
- `payloadRef == null` → `payload_key`, `payload_hash` = NULL, `JournalEntry.payloadRef()` = `null` (`PEJ:75-76`, `PEJ:373-374`) 🟢.
- `find` не использует транзакцию (autocommit по умолчанию); SQL-ошибка → `JournalException("find failed")` (`PEJ:104-111`) 🟢.

## Зависимости

`DataSource`, `ProcessingStatus.RECEIVED`/`DUPLICATE`; общая схема — [`../contracts.md`](../contracts.md) §1.

## Зависит от / связанные спецификации

См. [`../design.md`](../design.md) (раздел «Зависит от…»): `adapter-core` (`InboxWriter`), `graph-projector` (`Reconciler`), `ingestion-service` (`ReplayService`), `contracts/event-schemas`.

## Решения

| Решение | Evidence | Метка |
|---|---|---|
| `DUPLICATE` не персистируется; персистируемый `DUPLICATE` ставит позже другой код через `transition` (`RECEIVED → DUPLICATE` разрешён) | `PEJ:393-397`, `ES/ProcessingStatus.java:31-32`, `_reversa_sdd/database/business-rules.md` D1 | 🟢 |
| Результат конфликта читается тем же соединением и в той же транзакции | `PEJ:86-89` | 🟢 |

## Риски

- 🟡 Тело дубля не проверяется: подмена содержимого при повторном `eventId` не заметна (R-7).
- 🟡 Два разных смысла `DUPLICATE`: синтетический исход `append` и персистентный статус строки (ставится при равной версии, `docs/02-data-model.md:198`); вызывающий должен их не путать.
