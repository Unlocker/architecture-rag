# Блок-схемы: adapters/eam-adapter

> Archaeologist, R.2b. Пути относительно `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/`. Формат и допущения — `docs/06-eam-integration.md`, `docs/eam-api.yaml`.

## 1. `EamApiConnector.fetchChanges` (`EamApiConnector.java:93-132`) 🟢

```mermaid
flowchart TD
  A["fetchChanges(cursor, limit)"] --> P{"cursor == null?"}
  P -- да --> SS["EamApiCursor.snapshotStart(size)"]
  P -- нет --> PC["EamApiCursor.parse"]
  PC --> W{"режим WATERMARK?"}
  W -- да --> IS["incrementalStart"]
  W -- нет --> T
  SS --> T
  IS --> T["тип = TYPES[c.type]; sort = id (snapshot) / -mtime (инкремент)"]
  T --> L["GET /api/&lt;type&gt;/?page&amp;page_size&amp;sort"]
  L --> LP["для каждого объекта: change(); watermark = max(datetime)"]
  LP --> K{"инкремент и datetime ≤ from?"}
  K -- да --> RK["reachedKnown: прервать тип"]
  K -- нет --> LP
  RK --> TD
  LP --> TD{"reachedKnown или страница короче size?"}
  TD -- нет --> NP["hasMore=true, page+1"]
  TD -- да --> LT{"есть следующий тип?"}
  LT -- да --> NT["hasMore=true, type+1, page=1"]
  LT -- нет --> END["hasMore=false, nextCursor=W,watermark, snapshotComplete=(режим snapshot)"]
```

## 2. Запрос к EAM API (`EamApiConnector.get/send/throttle`, `:205-260`) 🟢

```mermaid
flowchart TD
  A[get] --> TH["throttle: ≥ 100 мс после предыдущего запроса"]
  TH --> S["send: GET с Authorization: Token"]
  S -- IOException --> U0["SourceUnavailableException(status 0)"]
  S --> C{status}
  C -- 429 --> RA{"есть Retry-After?"}
  RA -- да --> PA["пауза ≤ 30 с, повторный send"]
  PA --> C2{"429 или ≥ 500?"}
  RA -- нет --> C2
  C -- ">= 500" --> C2
  C2 -- да --> UN["SourceUnavailableException(status, Retry-After)"]
  C2 -- нет --> OK[200 / 404 — вернуть]
  C -- "200, 404" --> OK
```

Любой другой статус (в т. ч. 401/403) вызывающий превращает в `IllegalStateException` без тела (`requireOk`, `:241-246`) 🟢.

## 3. `CompositeEamConnector.fetchChanges` (`CompositeEamConnector.java:50-70`) 🟢

```mermaid
flowchart TD
  A["fetchChanges(cursor, limit)"] --> S{"cursor == null?"}
  S -- да --> ST["State(snapshot=true, фаза A)"]
  S -- нет --> PR["State.parse; чужой формат → инкремент с начала, WARN"]
  ST --> PH
  PR --> PH{"фаза L (legacy)?"}
  PH -- нет --> API["api.fetchChanges(s.api)"]
  API --> AP["страница с hasMore=true; фаза L, если api закончил"]
  PH -- да --> LG["legacy.fetchChanges(s.legacy); оставить только TEAM, SERVICE_DEPENDENCY"]
  LG --> M{"legacy hasMore?"}
  M -- да --> LM["hasMore=true, фаза L"]
  M -- нет --> SC{"snapshot и legacy без snapshotComplete?"}
  SC -- да --> X["IllegalStateException: маркер не ставится"]
  SC -- нет --> FIN["hasMore=false, snapshotComplete=(snapshot), режим I"]
```

## 4. `fetchById` составного коннектора (`CompositeEamConnector.java:88-96`) 🟢

```mermaid
flowchart LR
  F["fetchById(type, id)"] --> T{type}
  T -- "PLATFORM / SOLUTION / IT_SYSTEM" --> A[EamApiConnector]
  T -- "TEAM / SERVICE_DEPENDENCY" --> L[legacy HttpSourceConnector]
  T -- иной --> E[Optional.empty]
```

## 5. Жизненный цикл курсора (`EamApiCursor`, `EamApiCursor.java:10-14`) 🟢

```mermaid
stateDiagram-v2
  [*] --> S: cursor == null
  S --> S: следующая страница / тип
  S --> W: последняя страница последнего типа
  W --> I: следующий вызов (incrementalStart)
  I --> I: следующая страница / тип
  I --> W: тип исчерпан или встречен известный объект
```
