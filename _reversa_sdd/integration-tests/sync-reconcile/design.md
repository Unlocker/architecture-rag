# sync-reconcile: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Контракты: [`../contracts.md`](../contracts.md).

## Interface

| Символ | Сигнатура | Назначение | Трассировка |
|--------|-----------|------------|-------------|
| `SyncAcceptanceIT.startAdapters` | `@BeforeAll () -> void` | четыре адаптера над своими `StubSourceServer`, webhook и control на заранее выбранных свободных портах; polling не запускается | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:177-204` 🟢 |
| `SyncAcceptanceIT.PORTS` / `freePort()` / `controlUrl(system)` | | управляющие порты адаптеров, адрес нужен admin-сервису до старта адаптеров | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:122-141` 🟢 |
| `SyncAcceptanceIT.percentile(samples, p)` | `-> Duration` | `sorted[max(ceil(p/100·n),1) − 1]` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:453-457` 🟢 |
| `SyncAcceptanceIT.journalLag()` | `-> String` | p50 и max `updated_at − received_at` для `PROJECTED`, для печати | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:459-468` 🟢 |
| `ReconciliationIT.setUp` | `@BeforeAll static` | вручную: драйвер, `Neo4jSchema`, `JournalMigrations`, `GraphProjector`, `PostgresIdentityMapping`, `PostgresSourceConflicts`, `EventProcessor` с `Reconciler` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:88-106` 🟢 |
| `ReconciliationIT.pollerOver(connector)` | `-> Poller` | `Poller` с `AdapterConfig` (`RetryPolicy(2, 1 мс, 2 мс)`), `Random(1)`, генератором `syncRunId` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:123-129` 🟢 |
| `ReconciliationIT.drain()` | | прогон через `EventProcessor.process` всего, что адаптер записал в журнал, в порядке записи | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:131-137` 🟢 |
| `ReconciliationIT.FailingAfter` | `SourceConnector` | отдаёт `okCalls` страниц, дальше `SourceUnavailableException(EAM, 503)` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:297-324` 🟢 |
| `ReconciliationIT.NoopRawStore` / `RecordingJournal` | | сырое хранилище-заглушка (put даёт ссылку по UUID содержимого, get бросает); журнал PG, запоминающий добавленные события | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:326-399` 🟢 |
| `JournalDispatcherIT.poller(eam)` / `event(...)` / `status(id)` / `active(id)` | | `Poller` над `StubSource`, событие вручную, статус журнала, `SourceRecord.active` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:231-260` 🟢 |
| `EamKeyMigrationIT.prepare` | `@BeforeAll () -> void` | журнал, S3, `EamApiSupport`, старая заглушка EAM и SCM | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java:138-156` 🟢 |
| `EamKeyMigrationIT.activeItSystemKeys()` | `-> List<String>` | активные `sourceId` записей EAM `IT_SYSTEM` по возрастанию | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java:265-268` 🟢 |

## Fluxo Principal

**Сквозной цикл `SyncAcceptanceIT`** (`@Order(1..11)`, общее состояние):

```mermaid
sequenceDiagram
  participant T as тест
  participant S as StubSource
  participant A as Adapter+Poller
  participant J as PostgresEventJournal+S3
  participant D as JournalDispatcher (фон, 1 с)
  participant G as Neo4j
  T->>A: poller.pollOnce() ×4
  A->>S: fetchChanges(null) ... страницы
  A->>J: append(события) + маркер snapshot-complete
  D->>J: pending (RECEIVED/RETRYING)
  D->>G: EventProcessor → GraphProjector
  T->>G: count(...) (К1)
  T->>A: StubWebhookSender.send(event)
  A->>J: append(RECEIVED), 202
  D->>G: проекция; тест замеряет задержку (К2а)
  T->>S: suppressWebhooks, delete/upsert
  T->>D: POST /admin/reconcile/eam
  D->>A: POST control-url → snapshotOnce
  A->>J: события прогона + маркер
  D->>G: Reconciler → tombstone пропавшего (К2б)
  T->>D: POST /admin/rebuild?confirm=true
  D->>G: стереть граф, переиграть журнал; сравнить дамп (К4)
```

1. `@BeforeAll`: `StubSources.seeded(clock)`, по `StubSourceServer` и адаптеру на источник; `adapter.startWebhook(loopback:порт)` возвращает endpoint; `StubWebhookSender(endpoint, SECRET, clock)` на источник (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:177-204`).
2. К1: `pollOnce()` на каждом; `awaitJournalDrained()` (нет нетерминальных статусов); проверки Cypher и SQL (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:222-273`).
3. К2а: цикл из 20: `uid()`-имя, `upsert` в заглушке (TEAM через EAM, SERVICE через SCM), `webhookEvents().getLast()`, `send(event) == 202`, `awaitTrue(узел с именем есть, 60 с)`, замер `System.nanoTime()` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:277-309`).
4. К2б: `suppressWebhooks(true)` → `delete`+`upsert` → `Thread.sleep(2 × DISPATCHER_POLL)` → дамп не изменился → `POST /admin/reconcile/eam` (токен HS256) → `202` → `awaitTrue(teamActive)`; К2в: `eamApi.store.upsert(...)` + `pollOnce()` → `ADVANCED` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:311-362`).
5. К3: повтор webhook, обратный порядок, `reorderNextPage` + `pollOnce` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:366-432`).
6. К4: дамп до → `POST /admin/rebuild?confirm=true` → `200` → `awaitJournalDrained` → дамп после (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:436-449`).

**`ReconciliationIT`** (без Spring; ручное управление): `@BeforeEach` очищает Neo4j и `TRUNCATE dlq_entry, inbox_event, consumer_checkpoint`, создаёт новую `StubSource` и `Poller`; тест вызывает `poller.pollOnce()`/`snapshotOnce()`, затем `drain()`; проверки `active(id)` и `statusOf(prefix, id)` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:108-158`).

**`JournalDispatcherIT`** (Spring + реальный диспетчер): событие добавляется в журнал (`journal.append(event, rawRef, syncRunId)`) либо загружается `Poller`; `awaitTrue(…, 30 с)` ждёт статус; для маркера проверка `attempts ≥ 2` и `errorCode` вместо мгновенного `RETRYING` (переходный статус) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:121-227`).

## Fluxos Alternativos

- **Сбой источника посреди snapshot**: `pollerOver(new FailingAfter(eam, 1))` → `GAVE_UP`, маркер не записан (`statuses()` без `snapshot-complete:<run>`); `pollerOver(eam)` создаётся над тем же журналом и продолжает `syncRunId` из checkpoint (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:201-230`) 🟢.
- **Объект в карантине**: версия без обязательного поля (`Map.of()` вместо имени) попадает в snapshot, нормализатор отправляет событие в `QUARANTINED`, `Reconciler` считает объект присутствующим (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:232-246`) 🟢.
- **Окно snapshot**: после неудачного прохода (`GAVE_UP`) вручную добавляется webhook-событие нового объекта и обрабатывается; завершающий `snapshotOnce` не удаляет его (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:267-295`) 🟢.
- **Маркер неполного прогона**: `EventMapper.snapshotComplete(EAM, runId, now, 5L)` + raw с `objectCount = 5` и пустым прогоном в журнале; диспетчер повторяет маркер каждые `retry-delay`, `attempts` растёт, `errorCode = RECONCILIATION_TRIGGER_FAILED` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:193-216`) 🟢.
- **Маркер без raw**: `journal.append(marker, null, runId)` → `MARKER_NO_RAW`, одна открытая запись DLQ (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:218-227`) 🟢.
- **Миграция ключа**: порядок `m1…m5` обязателен: `adapters.getLast()` в `m2`/`m3` требует предшественников (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java:208`) 🟢.

## Dependências

`adapters/adapter-core` (`Poller`, `AdapterConfig`, `RetryPolicy`, `PollResult`, `EventMapper`), адаптеры EAM/SCM/CMDB/deploymap, `ingestion/event-journal`, `ingestion/ingestion-service` (диспетчер, `AdminLock`, admin REST), `ingestion/graph-projector` (`Reconciler`, `GraphProjector`, `EventProcessor`), `ingestion/normalizer`, `ingestion/identity-resolution`, `platform/source-stubs`. Спецификации: [`../../adapter-core/full-snapshot/design.md`](../../adapter-core/full-snapshot/design.md), [`../../adapter-core/receive-webhook/design.md`](../../adapter-core/receive-webhook/design.md), [`../../ingestion-service/dispatch-journal/design.md`](../../ingestion-service/dispatch-journal/design.md), [`../../graph-projector/design.md`](../../graph-projector/design.md), [`../../eam-adapter/composite-sync/design.md`](../../eam-adapter/composite-sync/design.md) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Реальные часы (`Clock.systemUTC()`) и реальные адаптеры в приёмке, чтобы замерять задержки | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:180` | 🟢 |
| Ожидание «ничего не должно измениться» — пауза 2 интервала диспетчера | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:328-329` | 🟡 слабая гарантия (Q-17) |
| Ручной сбой источника обёрткой `FailingAfter`, а не `StubSource.failNext` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:297-324` | 🟡 (`failNext` — «следующие N», нужно «после K»; Q-19 в [`../../source-stubs/questions.md`](../../source-stubs/questions.md)) |
| `RETRYING` — переходный, поэтому ждут `attempts ≥ 2` и `errorCode` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:208-214` | 🟢 |
| Канонический дамп сравнивает полностью, включая временные поля | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/CanonicalGraph.java:19-23` | 🟢 (🟡 охват меток и связей Q-18) |

## Observabilidade

`SyncAcceptanceIT.k2a` печатает `SLA webhook→graph: n=20 p50=… p95=… max=…; journal updated_at-received_at (PROJECTED): …` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:306-307`) 🟢. Метрики приложения в этих тестах не проверяются.

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 4); ответ владельца: вопросы 14] Q-03: нет проверки побочных эффектов при transient-сбое; 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 4); ответ владельца: вопросы 14] Q-10: курсор адаптера «после коммита Neo4j» не требуется — 🟢 [R.8: решение владельца, вопрос 4; UNLOCKER-283], ADR 0020 уточнён.
- 🟡 Q-15: имитации (`SourceConflictIT` подделывает маркер) — вне этого use case, но тот же `Reconciler`.
- 🟡 Q-17: `Thread.sleep(2000)`, p95 по реальному времени CI.
- 🟡 `SyncAcceptanceIT.k2c` опирается на сервис из `k2a`, `k4` — на tombstone из `k2b` и версии из `k3` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:360-361, 447`).
- 🟡 Q-18: дамп не охватывает `Solution`, `Platform`, `USES_*`, `DEPLOYED_FROM`.
- 🟡 Q-11: «зависший» маркер ретраится вечно до конца класса (П-1).
