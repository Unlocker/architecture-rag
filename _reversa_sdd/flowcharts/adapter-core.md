# Блок-схемы: adapters/adapter-core

> Archaeologist, R.2b. Пути относительно `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## 1. `Poller.pollOnce` и `snapshotOnce` (`Poller.java:89-118`) 🟢

```mermaid
flowchart TD
  A[pollOnce / snapshotOnce] --> L{"running.tryLock()?"}
  L -- нет --> AR[ALREADY_RUNNING]
  L -- да --> M{метод}
  M -- snapshotOnce --> S[snapshot]
  M -- pollOnce --> C{"checkpoint &lt;code&gt;-poller есть?"}
  C -- нет --> S
  C -- да --> I["incremental(cursor)"]
  S --> R[PollResult]
  I --> R
  S -. GaveUp .-> G[GAVE_UP]
  I -. GaveUp .-> G
  R --> U[unlock]
  G --> U
```

## 2. Инкрементальный polling (`Poller.incremental`, `Poller.java:120-145`) 🟢

```mermaid
flowchart TD
  S["cursor = checkpoint"] --> F["fetch(cursor)"]
  F --> W["для каждого изменения: inbox.record(poll:type/id/vN)"]
  W --> H{"hasMore и nextCursor пуст или равен cursor?"}
  H -- да --> X["IllegalStateException: нет прогресса"]
  H -- нет --> N{"nextCursor не null и новый?"}
  N -- да --> CP["saveCheckpoint(nextCursor); cursor = nextCursor"]
  N -- нет --> E
  CP --> E{hasMore?}
  E -- да --> F
  E -- нет --> O{"курсор сдвигался?"}
  O -- да --> AD[ADVANCED]
  O -- нет --> UP[UP_TO_DATE]
```

Курсор сохраняется только после `record` всей страницы (ловушка 6) 🟢.

## 3. Полный snapshot с маркером (`Poller.snapshot`, `Poller.java:147-188`) 🟢

```mermaid
flowchart TD
  A[snapshot] --> R{"checkpoint &lt;code&gt;-snapshot = run:id?"}
  R -- да --> RR["runId = id (продолжение)"]
  R -- нет --> NR["runId = новый UUID; saveCheckpoint(run:runId)"]
  RR --> F
  NR --> F["fetch(cursor=null)"]
  F --> W["record(snap:runId:type/id/vN, correlation=runId, sync_run_id=runId)"]
  W --> H{hasMore?}
  H -- да --> P{"nextCursor пуст или равен cursor?"}
  P -- да --> X["IllegalStateException: нет прогресса"]
  P -- нет --> F
  H -- нет --> C{"snapshotComplete и nextCursor?"}
  C -- нет --> XC["IllegalStateException: snapshot без snapshot-complete, маркер не пишется"]
  C -- да --> MK["record(snapshot-complete:runId, objectCount)"]
  MK --> D["saveCheckpoint(snapshot, done:runId)"]
  D --> PC["saveCheckpoint(poller, nextCursor)"]
  PC --> OK[SNAPSHOT_COMPLETED]
```

Порядок «маркер → done → курсор» — `Poller.java:168-172`; без маркера missing set не считается (ловушка 9) 🟢.

## 4. Повторы при недоступности (`Poller.fetch` + `RetryPolicy.delay`, `Poller.java:191-204`, `RetryPolicy.java:40-50`) 🟢

```mermaid
flowchart TD
  A["attempt = 1"] --> F[connector.fetchChanges]
  F -- ChangePage --> OK[вернуть страницу]
  F -- SourceUnavailableException --> L{"attempt &lt; maxAttempts?"}
  L -- нет --> G["GaveUp(status, appended, syncRunId)"]
  L -- да --> D["delay = max(Retry-After≤maxDelay, equal jitter(min(maxDelay, base·2^n-1)))"]
  D --> S[sleeper.sleep]
  S --> N["attempt++"] --> F
```

## 5. Приём webhook (`WebhookHandler.handle`, `WebhookHandler.java:56-107`) 🟢

```mermaid
flowchart TD
  A[POST /webhook] --> B{"тело ≤ 64 КБ?"}
  B -- нет --> R413[413]
  B -- да --> V{"WebhookSignature.verify = VALID?"}
  V -- нет --> R401[401]
  V -- да --> J{"тело — JSON-объект?"}
  J -- нет --> R400[400]
  J -- да --> K{"eventId, sourceType, sourceId заданы; source = code; eventId = заголовок?"}
  K -- нет --> R400
  K -- да --> FB["connector.fetchById"]
  FB -- SourceUnavailableException --> R503[503]
  FB --> P{"объект найден?"}
  P -- да --> REC
  P -- нет --> DL{"operation = DELETE?"}
  DL -- нет --> R404[404]
  DL -- да --> VER{"sourceVersion — число ≥ 0?"}
  VER -- нет --> R400
  VER -- да --> TB["tombstone по подписанному телу"]
  TB --> REC["inbox.record(eventId, ...)"]
  REC -- ошибка журнала или S3 --> R500["500 (WebhookServer)"]
  REC --> R202[202, в т. ч. DUPLICATE]
```

Проверка размера выполняется в `WebhookServer.process` до обработчика (`WebhookServer.java:108-115`); `500` — `WebhookServer.java:119-122` 🟢.

## 6. Запись в inbox (`InboxWriter.record`, `InboxWriter.java:34-48`) 🟢

```mermaid
sequenceDiagram
  participant C as Poller / WebhookHandler
  participant W as InboxWriter
  participant S as RawPayloadStore (S3)
  participant J as EventJournal (Postgres)
  C->>W: record(eventId, SourceChange, correlationId, syncRunId)
  W->>W: EventMapper.toEvent (null-значения верхнего уровня отбрасываются)
  W->>S: put(code, rawJson)
  S-->>W: RawPayloadRef
  W->>J: append(event, ref, syncRunId)
  J-->>W: JournalEntry (RECEIVED или DUPLICATE)
```

Ошибки S3 и журнала не перехватываются: вызывающий не должен считать событие принятым 🟢.

## 7. Расписание в `SourceAdapter` (`SourceAdapter.java:72-115`) 🟢

```mermaid
flowchart LR
  P["startPolling: scheduleWithFixedDelay(0, pollInterval)"] --> PO[Poller.pollOnce]
  R["startReconciliation: delay = interval = reconcileInterval"] --> SO[Poller.snapshotOnce]
  PO --> LK((общий ReentrantLock))
  SO --> LK
  PO -. RuntimeException .-> LG["лог: тип исключения; расписание продолжается"]
  SO -. RuntimeException .-> LG
```
