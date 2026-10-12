# Блок-схемы: ingestion/ingestion-service

> Archaeologist, R.2c. Пути относительно `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/`.

## Проход диспетчера (`JournalDispatcher.java:94-163`) 🟢

```mermaid
flowchart TD
  S["runOnce: retryNotAfter = now − retryDelay; after = null; blocked = ∅"] --> L{"stopped?"}
  L -- да --> END["вернуть handled"]
  L -- нет --> AL{"AdminLock.tryAcquire()"}
  AL -- занят --> END
  AL -- взят --> PG["reader.pending(after, retryNotAfter, batchSize)"]
  PG --> E{"страница пуста?"}
  E -- да --> END
  E -- нет --> EV["для каждого события"]
  EV --> B{"объект в blocked?"}
  B -- да --> EV
  B -- нет --> RAW{"есть payloadRef?"}
  RAW -- нет --> DLQ1["toDlq NO_RAW_PAYLOAD / MARKER_NO_RAW"]
  RAW -- да --> GET["rawStore.get(ref) (ошибка хранилища → событие остаётся, статус null)"]
  GET --> TE{"StoredEventReader.toEvent"}
  TE -- IllegalArgumentException --> DLQ2["toDlq INVALID_RAW_PAYLOAD"]
  TE -- ok --> PR["EventProcessor.process"]
  PR --> ST{"итог"}
  ST -- "null (исключение) или RETRYING" --> BL["blocked += объект"]
  ST -- иное --> NX["следующее событие"]
  BL --> NX
  NX --> REL["после страницы: освободить замок; after = ключ последнего"] --> L
```

## Админская операция (`AdminOperations.java:38-61`) 🟢

```mermaid
sequenceDiagram
  participant C as Клиент (scope architecture.admin, azp ∈ allow-list)
  participant K as AdminController
  participant O as AdminOperations
  participant L as AdminLock (pg advisory)
  participant A as admin_audit
  participant S as Replay/Rebuild/Adapter/Crosswalk
  C->>K: POST /admin/...
  K->>K: проверка параметров (400)
  K->>O: run(operation, actor, request, replayId, body)
  O->>L: acquire (занят → 409)
  O->>A: INSERT STARTED (сбой → операция не начинается)
  O->>S: body.get()
  alt сбой операции
    O->>A: UPDATE FAILED + класс исключения
    O-->>K: throw
  else успех
    O->>A: UPDATE SUCCEEDED + счётчики (сбой только логируется)
    O-->>K: результат
  end
  O->>L: release
```

## Replay и Rebuild (`ReplayService.java:63-100`, `RebuildService.java:33-39`) 🟢

```mermaid
flowchart TD
  RB["POST /admin/rebuild?confirm=true"] --> WIPE["MATCH (n) DETACH DELETE n по 10000 (constraints, PG, S3 не трогаются)"]
  WIPE --> RP
  RPQ["POST /admin/replay"] --> RP["replay(source?, from?, to?, replayId)"]
  RP --> PAGE["read страницами по 200, excludeReplays=true, порядок received_at"]
  PAGE --> ONE{"строка"}
  ONE -- "snapshot-complete" --> SK1["SKIPPED_SNAPSHOT_MARKER"]
  ONE -- "нет raw" --> SK2["SKIPPED_NO_RAW"]
  ONE -- иначе --> EV["toEvent(строка, raw)"]
  EV --> AP["append: eventId = replay:replayId:исходный → RECEIVED"]
  AP --> PR["EventProcessor.process"]
  PR --> CNT["statuses[итоговый статус]++"]
```

## Загрузка crosswalk (`CrosswalkService.java:35-64`) 🟢

```mermaid
flowchart TD
  I["элемент i"] --> AU["audit.start(CROSSWALK)"]
  AU --> CK["new Crosswalk(left, right, approvedBy=sub, reason, now)"]
  CK -- "IllegalArgumentException / NPE" --> INV["INVALID"]
  CK --> AP["identity.approve"]
  AP -- CrosswalkConflictException --> CF["CONFLICT"]
  AP -- иное RuntimeException --> FL["audit FAILED, исключение наружу"]
  AP -- ok --> OK["APPLIED + gid"]
  INV --> FIN["audit.finish"]
  CF --> FIN
  OK --> FIN
```

## Жизненный цикл `inbox_event` — кто двигает статус 🟢

```mermaid
stateDiagram-v2
  [*] --> RECEIVED: adapter / replay / reconcile → EventJournal.append
  RECEIVED --> DUPLICATE: версия равна применённой (EventProcessor)
  RECEIVED --> IGNORED_OLD_VERSION: версия ниже
  RECEIVED --> VALIDATED
  VALIDATED --> NORMALIZED
  NORMALIZED --> RESOLVED
  RESOLVED --> PROJECTED: Neo4j закоммитил + saveCheckpoint
  VALIDATED --> RETRYING: сбой PG / Neo4j
  NORMALIZED --> RETRYING
  RESOLVED --> RETRYING
  RECEIVED --> QUARANTINED: нет raw / битый raw / ошибка нормализации (DLQ)
  VALIDATED --> QUARANTINED
  RETRYING --> VALIDATED: повтор диспетчером после retry-delay
  RETRYING --> PROJECTED
  RETRYING --> QUARANTINED
  QUARANTINED --> RETRYING: допустим контрактом; вызова нет ни в одном модуле (выход — replay новой строкой)
  PROJECTED --> SUPERSEDED: допустим контрактом; вызова нет ни в одном модуле
```
