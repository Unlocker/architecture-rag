# Блок-схемы: ingestion/graph-projector

> Archaeologist, R.2c. Пути относительно `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/`. Статусы журнала — `data-dictionary.md` §E.3.

## `EventProcessor.process` (`EventProcessor.java:185-199`) 🟢

```mermaid
flowchart TD
  P["process(event, raw)"] --> F["find в журнале (нет → IllegalArgumentException)"]
  F --> FIN{"status ∈ PROJECTED, SUPERSEDED, DUPLICATE,\nIGNORED_OLD_VERSION, QUARANTINED?"}
  FIN -- да --> RET["вернуть текущий статус, без изменений"]
  FIN -- нет --> T{"type"}
  T -- "asset.upserted.v1" --> U["upsert"]
  T -- "asset.deleted.v1" --> D["delete"]
  T -- "sync.snapshot-complete.v1" --> S["snapshotComplete"]
  T -- иное --> Q["QUARANTINED UNSUPPORTED_EVENT_TYPE"]
```

## `upsert` (`EventProcessor.java:201-252`) 🟢

```mermaid
flowchart TD
  A["upsert"] --> SC{"sourceCode известен?"}
  SC -- нет --> Q1["QUARANTINED UNKNOWN_SOURCE"]
  SC -- да --> DB{"status == RECEIVED и запись уже применена?"}
  DB -- "версия равна" --> DUP["DUPLICATE + checkpoint"]
  DB -- "версия ниже" --> OLD["IGNORED_OLD_VERSION + checkpoint"]
  DB -- иначе --> V["→ VALIDATED"]
  V --> N["normalizer.normalize"]
  N -- Quarantined --> Q2["QUARANTINED (код normalizer)"]
  N -- Normalized --> W["warnings.record; unresolved → DeferRelation"]
  W --> NN["→ NORMALIZED"]
  NN --> G["resolveGids (identity.resolve для записи, find для чужих концов)"]
  G -- "нет gid у конца" --> R1["RETRYING UNRESOLVED_ENDPOINT"]
  G --> C["recordCandidates (PG)"]
  C -- IdentityStoreException --> R2["RETRYING IDENTITY_CANDIDATE_FAILED"]
  C --> ST{"RETRYING-повтор и версия ниже?"}
  ST -- да --> OLD2["IGNORED_OLD_VERSION"]
  ST -- нет --> CF["assertProperties, assertSystemRelation, assertSystemPlatform (PG)"]
  CF -- IdentityStoreException --> R3["RETRYING SOURCE_CONFLICT_FAILED"]
  CF --> RS["→ RESOLVED"]
  RS --> WR["write → GraphProjector.project (Neo4j)"]
```

## `write` и обработка ошибок (`EventProcessor.java:346-388`) 🟢

```mermaid
flowchart TD
  W["projection.project(ProjectionRequest)"] --> E{"исключение?"}
  E -- ProjectionException --> R1["RETRYING e.code"]
  E -- IllegalArgumentException --> Q1["QUARANTINED INVALID_COMMANDS"]
  E -- "Transient / ServiceUnavailable / SessionExpired" --> R2["RETRYING GRAPH_UNAVAILABLE"]
  E -- "ClientException Statement.* / Request.*" --> Q2["QUARANTINED GRAPH_REJECTED"]
  E -- "другой ClientException" --> R3["RETRYING GRAPH_CLIENT_ERROR"]
  E -- "другой Neo4jException" --> R4["RETRYING GRAPH_ERROR"]
  E -- нет --> O{"outcome"}
  O -- IGNORED_OLD_VERSION --> F1["finish IGNORED_OLD_VERSION + checkpoint"]
  O -- "APPLIED / NOOP_SAME_VERSION" --> RC["recheckServiceDeployments / recheckSolutionSystems (PG)"]
  RC -- IdentityStoreException --> R5["RETRYING SOURCE_CONFLICT_FAILED"]
  RC --> F2["finish PROJECTED + saveCheckpoint(projector, source, eventId)"]
```

## Транзакция проекции `GraphProjector.write` (`GraphProjector.java:302-359`) 🟢

```mermaid
flowchart TD
  A["executeWrite(tx)"] --> L["MERGE SourceRecord по ключу; SET/REMOVE _lock"]
  L --> VD{"VersionDecision"}
  VD -- SAME --> N1["NOOP_SAME_VERSION (ничего не пишется)"]
  VD -- OLD --> N2["IGNORED_OLD_VERSION"]
  VD -- APPLY --> DP["PendingRelations.deleteOf(запись)"]
  DP --> CMD{"команды по порядку"}
  CMD -- UpsertNode --> UN["upsertNode → healPending → healAliases"]
  CMD -- DeferRelation --> DF["PendingRelations.store"]
  CMD -- UpsertRelation --> UR["upsertRelation (authority? иначе skippedRelations)"]
  CMD -- Tombstone --> TS["tombstone: active=false, закрыть связи, архивировать узел без др. MASTER"]
  UN --> TAIL
  DF --> TAIL
  UR --> TAIL
  TS --> TAIL
  TAIL["closeReplacedRelations (не для tombstone) → markRelationOnlyRecord → writeConflictMarkers → linkSyncRun"] --> OK["APPLIED"]
```

## `Reconciler.reconcile` — missing set (`Reconciler.java:100-144`) 🟢

```mermaid
flowchart TD
  M["snapshot-complete(source, syncRunId, objectCount)"] --> SC["snapshotContents(source, syncRunId) из журнала"]
  SC --> CNT{"eventCount < objectCount?"}
  CNT -- да --> X["IllegalStateException → маркер RETRYING; ничего не удаляется"]
  CNT -- нет --> TC["touched = objectsReceivedSince(source, начало прогона)"]
  TC --> LOOP["для каждой активной записи источника в графе"]
  LOOP --> IN{"в прогоне?"}
  IN -- да --> K1["оставить (keptInSnapshot)"]
  IN -- нет --> RT{"touched?"}
  RT -- да --> K2["оставить (keptRecentlyTouched)"]
  RT -- нет --> J["journalTombstone: событие reconcile:… + raw в S3 (до записи в граф)"]
  J --> RE["retract конфликтов gid"]
  RE --> PJ["project(Tombstone, применённая версия)"]
  PJ --> FT["finishTombstone → PROJECTED / IGNORED_OLD_VERSION"]
```

## Решение по версии (`VersionDecision.java:20-38`) 🟢

```mermaid
flowchart TD
  A{"applied == null?"} -- да --> AP["APPLY"]
  A -- нет --> C{"incoming vs applied"}
  C -- ">" --> AP
  C -- "<" --> OLD["OLD"]
  C -- "==" --> E{"это tombstone и запись активна?"}
  E -- да --> AP
  E -- нет --> SAME["SAME (NOOP / DUPLICATE)"]
```
