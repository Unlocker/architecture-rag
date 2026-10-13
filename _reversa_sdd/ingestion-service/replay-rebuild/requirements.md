# replay-rebuild: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Visão Geral

Админские операции восстановления витрины из журнала: `replay` переигрывает события источника в диапазоне времени приёма под новым `replayId`; `rebuild` сначала стирает все узлы Neo4j, затем переигрывает весь журнал.

## Regras de Negócio

- Replay читает журнал страницами по 200 в порядке `(received_at, source, event_id)`, полуинтервал `[receivedFrom, receivedTo)`, **без строк самих replay** (`excludeReplays = true`) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:38, 67-74`; `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalQuery.java:18-24`).
- Для каждой исходной строки создаётся **новая** строка журнала `eventId = replay:<replayId>:<исходный eventId>` с исходными `payloadRef` и `syncRunId`, тем же `correlationId`/типом/субъектом/временем; она проходит `EventProcessor.process`; исходные строки не меняются 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:88-98`).
- `snapshot-complete` пропускается (`SKIPPED_SNAPSHOT_MARKER`), чтобы не запускать reconciliation повторно; строка без `payloadRef` → `SKIPPED_NO_RAW` 🟢 (`:80-86`).
- Повтор с тем же `replayId` идемпотентен: `append` отсекает дубль, `process` возвращает уже достигнутый конечный статус 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:26-27`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:99-105, 190-192`).
- Итог — `ReplayResult{replayId, total, statuses}`; `total` — число просмотренных строк, `statuses` — счётчики по итоговому статусу 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayResult.java:13`).
- Ошибки raw storage и журнала не глушатся: replay прерывается; повтор безопасен 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:30`).
- Rebuild: `MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS` через `session.run` (неявная транзакция), затем `replay(null, null, null, replayId)` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18, 33-39`). Constraints/индексы, `identity_mapping`, crosswalk, checkpoints адаптеров не трогаются, поэтому `gid` прежние 🟢 (Javadoc `:9-12`).
- `rebuild` всегда с новым `replayId` (`UUID.randomUUID()`); `confirm=true` обязателен 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:86-89`).
- Валидация `replay`: `source` обязателен и по `[A-Za-z0-9:._-]{1,200}`; `receivedFrom < receivedTo`; `replayId` — UUID 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:63-70, 140-149`).
- 🔴 Строка журнала с битым raw (то, что диспетчер ранее отправил в `QUARANTINED/INVALID_RAW_PAYLOAD`) или с расхождением hash в S3 прерывает весь replay/rebuild: `replayOne` не ловит `IllegalArgumentException` из `toEvent` и `IllegalStateException` из `rawStore.get`, а `read` не фильтрует по статусу (Q-04) 🟢 факт кода / 🔴 намерение.
- 🟡 `rebuild` стирает **все** узлы базы Neo4j, не только проекцию ingestion (Н-13 в `../../tech-debt.md:68`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `POST /admin/replay` с диапазоном и фильтром источника | Must | AC-01, AC-02 |
| RF-02 | Идемпотентный повтор по `replayId` | Must | AC-02 |
| RF-03 | `POST /admin/rebuild?confirm=true` | Must | AC-03, AC-04 |
| RF-04 | Tombstone `Reconciler` переживает rebuild (воспроизводится из журнала) | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Performance | Страница чтения 200; удаление пачками по 10000 | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:38`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18` | 🟢 |
| Disponibilidade | Операции синхронные, под `AdminLock`, диспетчер приостановлен на время | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:75-91`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:45` | 🟢 |
| Segurança | В аудит — параметры и счётчики, не payload | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:71-74`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java:15-18` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (AdminControllerTest.replayWithScopeReturnsResultAndPassesActor)
Дано токен scope architecture.admin, sub = alice
Когда POST /admin/replay {"source":"urn:corp:eam"}
Тогда 200 с {replayId,total,statuses}; операция "REPLAY" выполнена от имени alice

# AC-02 (AdminEndpointIT.replayIsIdempotentForSameReplayIdAndRestoresLostProjection; replayOfEmptyRangeDoesNothing)
Дано выполнен replay с replayId R, затем повторён с тем же R
Когда журнал содержит строки replay:R:*
Тогда новых строк нет, статусы те же; пустой диапазон даёт total = 0

# AC-02n (AdminControllerTest.replayRejectsBadParameters)
Дано source пуст/с пробелом, либо receivedFrom >= receivedTo, либо replayId не UUID
Когда POST /admin/replay
Тогда 400 {"error": ...} и сервис replay не вызван

# AC-03 (AdminEndpointIT.rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt; rebuildReproducesRelationsThatWereDeferredUntilTheirEndpointsArrived)
Дано граф стёрт
Когда POST /admin/rebuild?confirm=true
Тогда граф эквивалентен исходному, admin_audit содержит REBUILD со SUCCEEDED

# AC-04 (AdminControllerTest.rebuildRequiresConfirm; AdminEndpointIT.rebuildWithoutConfirmIsRejectedAndGraphIsUntouched)
Дано нет confirm=true
Когда POST /admin/rebuild
Тогда 400 и узлы графа не тронуты

# AC-05 (AdminEndpointIT.reconcileTombstoneSurvivesRebuild)
Дано объект закрыт Reconciler-ом (событие reconcile:*)
Когда выполнен rebuild
Тогда объект остаётся закрытым
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-03 | Must | Единственный способ восстановить граф из журнала (ADR 0013) |
| RF-04 | Must | Воспроизводимость удалений (ADR 0020, К-5) |
| Пропуск карантинных строк при replay | Won't | Нет в коде (Q-04) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java` | `ReplayService` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java` | `RebuildService` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayResult.java` | `ReplayResult` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java` | `replay`, `rebuild` | 🟢 |
