# Блок-схемы: ingestion/event-journal

> Archaeologist, R.2c. Пути относительно `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/`. Статусы — `ProcessingStatus` (`contracts/event-schemas`), см. `flowcharts/event-schemas.md`.

## `append`: запись события в inbox (`PostgresEventJournal.java:53-97`) 🟢

```mermaid
flowchart TD
  A["append(event, payloadRef, syncRunId)"] --> B["BEGIN (autoCommit=false)"]
  B --> C["INSERT inbox_event status=RECEIVED attempts=0\nON CONFLICT (source,event_id) DO NOTHING RETURNING"]
  C --> D{"строка вернулась?"}
  D -- да --> E["COMMIT → JournalEntry(RECEIVED)"]
  D -- нет, дубль --> F["SELECT существующей строки"]
  F --> G{"нашлась?"}
  G -- нет --> X["JournalException: row vanished"]
  G -- да --> H["COMMIT → копия JournalEntry со статусом DUPLICATE\n(в БД статус не меняется)"]
  C -. SQLException/RuntimeException .-> R["ROLLBACK → JournalException(append failed)"]
```

`publish` = `append(...).status() != DUPLICATE` (`:99-102`).

## `transition` / `toDlq` (`:113-160`, `:320-354`) 🟢

```mermaid
flowchart TD
  T["transition(source,eventId,status,code,reason)"] --> L["SELECT … FOR UPDATE"]
  L --> N{"строка есть?"}
  N -- нет --> E1["IllegalArgumentException unknown event + отпечаток"]
  N -- да --> P{"current.canTransitionTo(status)?"}
  P -- нет --> E2["IllegalStateException transition X → Y"]
  P -- да --> U["UPDATE status, error_code, error_reason(≤500),\nattempts += (status==RETRYING ? 1 : 0), updated_at=now"]
  U --> C["COMMIT"]
  D["toDlq(code, reason)"] --> V{"code пуст?"}
  V -- да --> E3["IllegalArgumentException"]
  V -- нет --> Q["update(→QUARANTINED)"]
  Q --> I["INSERT dlq_entry … ON CONFLICT (source,event_id) WHERE replayed_at IS NULL DO NOTHING"]
  I --> C
```

## Выборка для диспетчера `pending` (`:209-237`) 🟢

```mermaid
flowchart LR
  S["inbox_event"] --> F{"status"}
  F -- RECEIVED --> OK["берём сразу"]
  F -- "RETRYING / VALIDATED / NORMALIZED / RESOLVED" --> W{"updated_at ≤ retryNotAfter?"}
  W -- да --> OK
  W -- нет --> SK["пропуск до следующего прохода"]
  F -- "QUARANTINED / PROJECTED / DUPLICATE / SUPERSEDED / IGNORED_OLD_VERSION" --> SK2["не выбираются"]
  OK --> K["ORDER BY received_at, source, event_id; keyset (received_at,source,event_id) > after; LIMIT"]
```

## S3: `put` / `get` (`S3RawPayloadStore.java:68-99`) 🟢

```mermaid
flowchart TD
  P["put(source, bytes)"] --> V{"source ∈ [A-Za-z0-9:._-]{1,200}\nи не только точки?"}
  V -- нет --> X["IllegalArgumentException"]
  V -- да --> H["hash = SHA-256(bytes); key = raw/source/hash"]
  H --> HE{"HEAD key"}
  HE -- есть --> R["RawPayloadRef(key, hash)"]
  HE -- NoSuchKey --> PU["PUT" ] --> R
  G["get(ref)"] --> GE["GET bytes"] --> CH{"SHA-256 == ref.contentHash?"}
  CH -- да --> OUT["bytes"]
  CH -- нет --> X2["IllegalStateException hash mismatch"]
```
