# ERD: граф Neo4j и реляционная часть PostgreSQL

> Data Master, этап R.3 (UNLOCKER-268). Диаграммы построены по коду и миграциям, а не по `docs/02-data-model.md`. Расхождения с документом собраны в [`data-dictionary.md`](data-dictionary.md#9-расхождения-с-docs02-data-modelmd). Метки уверенности: 🟢 прочитано в DDL/коде · 🟡 выведено · 🔴 расхождение или пробел.
> В R.5 Architect ссылается на этот файл и не рисует ERD заново.

## 1. Хранилища и связи между ними

В хранилищах нет внешних ключей друг в друга: связь идёт по значениям (`gid`, `source`, `source_type`, `source_id`, `payload_key`). Единственный FK на весь PostgreSQL — `dlq_entry → inbox_event` (`ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:44`) 🟢.

```mermaid
flowchart LR
    S3[(S3 bucket: raw/&lt;source&gt;/&lt;sha256&gt;)]
    PG[(PostgreSQL: 10 таблиц, public)]
    NEO[(Neo4j Community: 16 меток, 18 constraints, 2 индекса)]
    PG -- "inbox_event.payload_key + payload_hash" --> S3
    PG -- "identity_mapping.gid = Node.gid (по значению)" --> NEO
    PG -- "source_conflict.gid, property_assertion.gid (по значению)" --> NEO
    PG -- "ключ записи (source_type, source_id) совпадает с SourceRecord.(sourceType, sourceId); source: URN в inbox_event, код SourceSystemCode в identity_mapping и SourceRecord" --> NEO
    NEO -- "SourceRecord.contentHash = payload_hash" --> S3
```

Источники связей: `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:74-78`, `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:56-59` 🟢. Связь `identity_mapping.gid` → `Node.gid` логическая: при `rebuild` граф стирается (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18`), а `identity_mapping` остаётся, поэтому `gid` прежние 🟢.

## 2. PostgreSQL

Все 10 таблиц лежат в схеме `public`; время везде `timestamptz`. Версии миграций сквозные: V1 (event-journal), V2/V4/V5/V7 (identity-resolution), V3/V6 (ingestion-service); подробности порядка — [`data-dictionary.md`, §3](data-dictionary.md#3-flyway-порядок-применения-и-владельцы-версий).

```mermaid
erDiagram
    inbox_event {
        text source PK "UNIQUE(source,event_id)"
        text event_id PK
        text type
        text subject "NULL"
        text source_type
        text source_id
        text source_version
        text correlation_id "NULL"
        text sync_run_id "NULL"
        text schema_version
        text status "без CHECK"
        int attempts "DEFAULT 0"
        text error_code "NULL"
        text error_reason "NULL"
        text payload_key "NULL -> S3"
        text payload_hash "NULL"
        timestamptz received_at
        timestamptz updated_at
    }
    dlq_entry {
        bigserial id PK
        text source FK
        text event_id FK
        text reason
        text error_code
        text payload_key "NULL"
        timestamptz created_at
        timestamptz replayed_at "NULL, не заполняется кодом"
    }
    consumer_checkpoint {
        text consumer PK
        text source PK
        text cursor
        timestamptz updated_at
    }
    admin_audit {
        bigserial id PK
        text operation
        text actor
        jsonb request
        text replay_id "NULL"
        text status
        jsonb result "NULL"
        text error "NULL"
        timestamptz started_at
        timestamptz finished_at "NULL"
    }
    identity_mapping {
        text source PK
        text source_type PK
        text source_id PK
        uuid gid
        timestamptz created_at
    }
    approved_crosswalk {
        text left_source PK
        text left_type PK
        text left_id PK
        text right_source PK
        text right_type PK
        text right_id PK
        text approved_by
        text reason
        timestamptz approved_at
    }
    identity_feature {
        text source PK
        text source_type PK
        text source_id PK
        text feature PK
        text value
        text label_family
        timestamptz updated_at
    }
    identity_candidate {
        text left_source PK
        text left_type PK
        text left_id PK
        text right_source PK
        text right_type PK
        text right_id PK
        text label_family
        jsonb matched
        numeric score "3,2"
        text status "DEFAULT OPEN"
        timestamptz first_seen_at
        timestamptz updated_at
    }
    property_assertion {
        text source PK
        text source_type PK
        text source_id PK
        text property PK
        uuid gid
        text label
        text value
        boolean authoritative
        timestamptz asserted_at
    }
    source_conflict {
        uuid gid PK
        text property PK
        text dissent_source PK
        text dissent_type PK
        text dissent_id PK
        text dissent_value
        text master_value
        text master_source
        text master_type
        text master_id
        text status "DEFAULT OPEN"
        text kind "DEFAULT PROPERTY, V7"
        timestamptz opened_at
        timestamptz updated_at
        timestamptz resolved_at "NULL"
    }
    inbox_event ||--o{ dlq_entry : "FK (source,event_id)"
    inbox_event }o..o| consumer_checkpoint : "логически: consumer_checkpoint.source = inbox_event.source"
    identity_mapping ||..o{ identity_feature : "логически: ключ (source,source_type,source_id)"
    identity_mapping ||..o{ property_assertion : "логически: ключ записи; gid совпадает"
    property_assertion ||..o{ source_conflict : "логически: gid + property"
    identity_mapping ||..o{ approved_crosswalk : "логически: обе стороны пары"
    identity_feature ||..o{ identity_candidate : "логически: кандидат собран из совпавших признаков"
```

Сплошная линия — FK (`ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:44`). Пунктир — связь по значению, в DDL её нет; это выведено из кода репозиториев 🟡 (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java`, `PostgresIdentityCandidates.java`, `PostgresIdentityMapping.java`). В `inbox_event` PK нет: ключом служит `UNIQUE (source, event_id)`, поэтому на диаграмме эти колонки помечены `PK` условно 🟢 (`ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:21`).

## 3. Граф Neo4j

Метки и типы связей перечислены по `NodeLabel` и `RelationType`. Каждая каноническая метка (13) имеет UNIQUE на `gid`; провенанс-метки (3) — собственные ключи; `PendingRelation` — служебная метка вне enum.

```mermaid
erDiagram
    ITSystem ||--o{ Service : DECOMPOSED_INTO
    ITSystem }o--o{ Team : OWNED_BY
    ITSystem }o--o{ Solution : USES_SOLUTION
    Solution }o--o{ Platform : USES_PLATFORM
    Service }o--o{ Team : OWNED_BY
    Service }o--o{ Service : DEPENDS_ON
    Service }o--o{ Repository : IMPLEMENTED_IN
    Service ||--o{ Deployment : HAS_DEPLOYMENT
    Deployment }o--|| Environment : IN_ENVIRONMENT
    Deployment }o--o{ Repository : DEPLOYED_FROM
    Deployment }o--o{ ComputeInstance : RUNS_ON
    Deployment }o--o{ Namespace : RUNS_ON
    Namespace }o--|| KubernetesCluster : PART_OF
    VirtualMachine }o--o| PhysicalServer : HOSTED_ON
    ComputeInstance ||--o| VirtualMachine : "доп. метка (подтип)"
    ComputeInstance ||--o| PhysicalServer : "доп. метка (подтип)"
    SourceSystem ||--o{ SourceRecord : OWNS_RECORD
    SyncRun ||--o{ SourceRecord : PROCESSED
    SourceRecord }o--o{ ITSystem : "ASSERTS (любой канонический узел)"
    SourceRecord ||--o{ PendingRelation : DEFERS
```

Свойства связей: `DEPENDS_ON {kind, protocol, criticality}`, `DEPLOYED_FROM {commit}`, `ASSERTS {confidence, authority, conflicts}`; временные (`validFrom`, `validTo`) — `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `DECOMPOSED_INTO`, `IMPLEMENTED_IN`, `DEPLOYED_FROM`, `RUNS_ON`, `DEPENDS_ON`, `OWNED_BY`, `USES_SOLUTION`, `USES_PLATFORM`; на бизнес-связях проектор пишет `assertedBySource/Type/Id`. Полный список свойств узлов и связей — [`_reversa_sdd/data-dictionary.md`](../data-dictionary.md), §A.1–A.3 и §Q.4. `HOSTED_ON`, `PART_OF`, `Namespace`, `KubernetesCluster` моделируются, но не наполняются (🟡, `docs/adr/0011-namespace-cluster-modeled-not-populated.md`).

### 3.1. Какой constraint стоит на каком узле

| Метка | Ключ `MERGE` | Constraint (имя) | Определение |
|---|---|---|---|
| 13 канонических | `gid` | `<snake>_gid` (по одному на метку) | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-46` |
| `Environment` | `gid`; дополнительно `code` | `environment_code` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:54-55` |
| `SourceRecord` | `(source, sourceType, sourceId)` | `source_record_key` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:47-48` |
| `SourceSystem` | `code` | `source_system_code` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:49-50` |
| `SyncRun` | `runId` | `sync_run_id` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:51` |
| `PendingRelation` | `key` | `pending_relation_key` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:52-53` |
| `ITSystem` | — (поиск по `eamCode`) | индекс `it_system_eam_code` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:56` |
| `ITSystem`, `Service` | — | FULLTEXT `asset_text` по `name`, `description` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:57-58` |
