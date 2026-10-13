# replay-rebuild: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (замок, аудит). Контракт REST: [`../contracts.md`](../contracts.md) §1. Чтение журнала: [`../../event-journal/read-journal/design.md`](../../event-journal/read-journal/design.md). Процессор: [`../../graph-projector/`](../../graph-projector/).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `ReplayService.replay` | `(String source /*nullable = все*/, Instant receivedFrom, Instant receivedTo, String replayId)` | `ReplayResult(replayId, total, TreeMap statuses)`; `NullPointerException("replayId")` при `null` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:63-76` 🟢 |
| `RebuildService.rebuild` | `(String replayId)` | `ReplayResult` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:33-39` 🟢 |
| `RebuildService.WIPE` | `"MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS"` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18` 🟢 |
| `JournalQuery.REPLAY_PREFIX` | `"replay:"` | | `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalQuery.java:22` 🟢 |

## Fluxo Principal (replay)

1. `AdminController.replay` валидирует вход, нормализует `replayId` и вызывает `operations.run("REPLAY", sub, audited, replayId, () -> replay.replay(...), summary)` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:61-82`).
2. `query = JournalQuery(source, from, to, excludeReplays=true, after=null, limit=200)`; цикл `reader.read(query)` до пустой страницы, `query.after(JournalKey.of(последняя))` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:67-74`).
3. Для каждой строки `replayOne` (`:78-100`):
   1. тип `architecture.sync.snapshot-complete.v1` → `SKIPPED_SNAPSHOT_MARKER`;
   2. `payloadRef == null` → `SKIPPED_NO_RAW`;
   3. `original = events.toEvent(stored, rawStore.get(ref))`;
   4. `replayed = CanonicalEvent(id = "replay:" + replayId + ":" + original.id(), source, type, subject, time, dataschema, correlationid, data)`;
   5. `journal.append(replayed, ref, entry.syncRunId())` (дубль отсекается журналом);
   6. `processor.process(replayed, ref).status().name()`.
4. `statuses.merge(status, 1, sum)`, `total++`.

## Fluxo Principal (rebuild)

1. `confirm=true` проверяется до замка и аудита (`400 confirm=true is required`); `replayId = UUID.randomUUID()` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:86-89`).
2. `operations.run("REBUILD", sub, {"confirm": true}, replayId, () -> rebuild.rebuild(replayId), summary)`.
3. `driver.session()` (try-with-resources) → `session.run(WIPE).consume()` — не `executeWrite`: `CALL IN TRANSACTIONS` допустим только в неявной транзакции (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:34-37`).
4. `replay.replay(null, null, null, replayId)` — все источники, весь журнал (`:38`).

## Fluxos Alternativos

- **Замок занят** → `409`, ничего не стёрто и не переиграно (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java:32-34`).
- **Исключение посреди replay/rebuild** → `admin_audit` = `FAILED` с классом исключения; replay можно повторить с тем же `replayId`; после прерванного rebuild граф неполон, операцию нужно повторить целиком (Javadoc `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:10-12`) 🟢.
- **Короткий код источника** (`eam` вместо `urn:corp:eam`) проходит валидацию, но фильтр `source = ?` ничего не найдёт: `total = 0` 🟡.
- **Строки `reconcile:*`** (tombstone `Reconciler`) не имеют префикса `replay:` и переигрываются; поэтому удаления воспроизводятся (`AdminEndpointIT.reconcileTombstoneSurvivesRebuild`) 🟡.

## Dependências

`JournalReader.read`, `EventJournal.append`, `RawPayloadStore.get`, `StoredEventReader`, `EventProcessor` (все — через конструктор, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:46-57`), Neo4j `Driver` (rebuild).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Новая строка журнала вместо переписывания старой | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:24-28, 88-97` | 🟢 |
| Идемпотентность по `eventId`-префиксу, а не по состоянию | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:26-27` | 🟢 |
| Маркеры не переигрываются | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:28, 80-82` | 🟢 |
| Стирание пачками вне `executeWrite` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:17, 34` | 🟢 |

## Observabilidade

Логи `admin operation started/finished/failed` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:47, 53, 58`); метрика `archrag.ingestion.events` replay'ем не увеличивается (вызов `processor.process` напрямую, без `metrics.recordEvent`) 🟡. `archrag.normalizer.unmapped.values` растёт и при replay (Javadoc `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:34-38`).

## Riscos e Lacunas

- 🔴 Q-04: `replayOne` не пропускает и не считает строки с битым raw / hash mismatch; весь replay/rebuild падает на первой такой строке. Диспетчер сам создаёт такие строки (`QUARANTINED/INVALID_RAW_PAYLOAD`), `PostgresEventJournal.read` статус не фильтрует (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:162-207`). Для rebuild это означает уже стёртый граф и невозможность довести replay. Теста нет.
- 🟡 Rebuild не атомарен: между стиранием и концом replay граф неполон, MCP отдаёт неполные ответы.
- 🟡 В `tech-debt.md` Н-13: стирается вся база, не только проекция.
