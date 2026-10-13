# dispatch-journal: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (замок `AdminLock`, метрики, сборка бинов). Контракты: [`../contracts.md`](../contracts.md) §5-6. Журнал: [`../../event-journal/read-journal/design.md`](../../event-journal/read-journal/design.md); процессор: [`../../graph-projector/`](../../graph-projector/).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `JournalDispatcher(...)` | `(JournalReader, EventJournal, RawPayloadStore, StoredEventReader, EventProcessor, AdminLock, Clock, int batchSize, Duration retryDelay, IngestionMetrics)` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:65-86` 🟢 |
| `runOnce` | `() -> int` | число событий, взятых в обработку (включая отправленные в DLQ и упавшие) | `:94-130` 🟢 |
| `stop` | `() -> void` | | `:89-91` 🟢 |
| `DispatcherRunner implements SmartLifecycle` | `start/stop/isRunning`, `@ConditionalOnProperty(archrag.dispatcher.enabled, true, matchIfMissing)` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:16-76` 🟢 |
| `DispatcherProperties` | `(enabled=true, pollInterval=1s, batchSize=100, retryDelay=30s, shutdownTimeout=30s)` | валидация при создании | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherProperties.java:16-34` 🟢 |
| `StoredEventReader.toEvent` | `(StoredEvent, byte[]) -> CanonicalEvent` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:44-86` 🟢 |

## Fluxo Principal

1. `DispatcherRunner.start`: однопоточный `ScheduledExecutorService` (поток `journal-dispatcher`, демон), `scheduleWithFixedDelay(pass, 0, pollInterval)`; `pass` ловит `RuntimeException` и логирует класс (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:32-52`).
2. `runOnce`: `blocked = {}`, `retryNotAfter = clock.instant() − retryDelay`, `after = null` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:95-98`).
3. Пока `!stopped`: `lock.tryAcquire()`; пусто → `LOG.debug`, `return handled` (`:99-104`).
4. Под замком (`try-with-resources`): `page = reader.pending(after, retryNotAfter, batchSize)`; для каждого события: если `stopped` → `return handled`; ключ объекта `source \0 sourceType \0 sourceId`; в `blocked` → `continue`; иначе `dispatch(stored)`, `handled++`; статус `null` или `RETRYING` → `blocked.add(object)` (`:105-123`).
5. Замок освобождается после страницы. Пустая страница → `return handled`; иначе `after = JournalKey.of(последняя строка)` (`:124-127`).
6. `dispatch` = `process` + `metrics.recordEvent(source, status)` (`:133-137`).
7. `process`:
   1. `ref == null` → `toDlq(..., NO_RAW_PAYLOAD | MARKER_NO_RAW)` (`:142-146`).
   2. `raw = rawStore.get(ref)` (ошибка хранилища идёт наружу) (`:148`).
   3. `events.toEvent(stored, raw)`; `IllegalArgumentException` → `toDlq(INVALID_RAW_PAYLOAD)` (`:149-154`).
   4. `processor.process(event, ref).status()` (`:155`).
   5. `catch (RuntimeException)` → `LOG.error(source, eventId, className)`, вернуть `null` (`:156-162`).

## Fluxos Alternativos

- **Замок занят** — проход заканчивается без чтения журнала (`JournalDispatcherTest.busyAdminLockSkipsPassWithoutReadingJournal`).
- **`toDlq` бросил исключение** (например, журнал недоступен) — попадает в общий `catch RuntimeException` → `null`/`ERROR`, объект блокируется 🟡 (вывод из структуры `try`, `:141-162`).
- **`stop()` во время страницы** — `return handled` изнутри `try-with-resources`, замок освобождается 🟢 (`:109-111`).
- **Исключение выше `process`** (например, `reader.pending` бросил `JournalException`) — выходит из `runOnce`, ловится в `DispatcherRunner.pass` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:46-52`).

## `StoredEventReader.toEvent` (порядок шагов)

1. Разбор `new String(raw, UTF_8)` в `JsonNode`; `JacksonException` → `IllegalArgumentException("raw payload is not valid JSON")`; не объект → `"raw payload is not a JSON object"` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:46-54`).
2. Тип `architecture.sync.snapshot-complete.v1` → `marker(...)`: `objectCount` обязателен, целый и `>= 0`; payload = `{syncRunId = entry.sourceId(), objectCount: Long}`; `time` из `updatedAt` (`:55-57, 88-106`).
3. `delete = "DELETE".equals(raw.operation)`; `time = Instant.parse(raw.updatedAt)` (ошибка/`null` → `"raw payload has no valid updatedAt"`) (`:58-59, 108-115`).
4. Не `DELETE`: `payload` (если объект) → `Map`, поля со значением `null` отбрасываются (только верхний уровень); `completeness` обязателен (иначе `"raw payload has no completeness"`), кладётся как `_completeness` (`:60-76`). Любое иное значение `operation` трактуется как upsert 🟡.
5. `CanonicalEvent(entry.eventId, entry.source, stored.type, stored.subject, time, entry.schemaVersion, entry.correlationId, AssetEventData(entry.sourceType, entry.sourceId, SourceVersion(entry.sourceVersion.value), payload))` (`:77-85`).
6. `JsonMapper` пересобран с `USE_LONG_FOR_INTS` (целые → `Long`) (`:39-41`).

## Dependências

- `JournalReader`, `EventJournal` (`toDlq`), `RawPayloadStore` (`get`, с проверкой SHA-256: `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:81-89`), `EventProcessor` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:185-199`), `AdminLock`, `IngestionMetrics`.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Пропуск объекта ради порядка версий внутри объекта | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:35-36, 113-121` | 🟢 |
| DLQ из диспетчера для строк, не доходящих до `process` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:40-43` | 🟢 |
| Ошибки хранилища не в DLQ | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:147` | 🟢 |
| `TYPE_SNAPSHOT_COMPLETE` продублирован литералом | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:34` | 🟢 (риск Q-05) |

## Estado Interno

`stopped` (volatile) не сбрасывается (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:63, 90`); `blocked`, `after` живут один вызов `runOnce`.

## Observabilidade

Логи: `debug admin operation in progress, dispatcher pass skipped`; `error dispatch failed: source= eventId= cause=`; `error dispatcher pass failed: cause=`; `warn dispatcher did not stop within`. Метрика `archrag.ingestion.events` (см. [`../contracts.md`](../contracts.md) §3).

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] Нет лимита повторов; бесконечный `RETRYING`/исключение — Q-01.
- 🟡 После `stop()` повторный `start()` не возобновляет работу (`stopped` необратим) — Q-09.
- 🟡 Замок берётся на страницу из `batchSize` событий; длинная страница удерживает админские операции (они получают 409) — Q-06.
- 🟡 Ссылка Н-2 в `../../tech-debt.md:57` на `JournalDispatcher.java:100` указывает на `tryAcquire`, а не на место повтора (повтор — `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:119-121, 156-162` и `PostgresEventJournal.java:209-217`).
