# Связи между сущностями хранилищ

> Data Master, этап R.3 (UNLOCKER-268). Кардинальности и тип связи (FK, логическая, граф). Диаграммы — [`erd.md`](erd.md). Метки: 🟢 DDL/код · 🟡 выведено · 🔴 расхождение.

## 1. PostgreSQL

| Связь | Кардинальность | Вид | Основание |
|---|---|---|---|
| `inbox_event` → `dlq_entry` | 1 : 0..N (но не больше одной открытой, `replayed_at IS NULL`) | **FK** `FOREIGN KEY (source, event_id)` без `ON DELETE`; частичный `UNIQUE` на открытые | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:44, 48` 🟢 |
| `inbox_event` ↔ `consumer_checkpoint` | N : 1 по `source` | логическая: `consumer_checkpoint.source` совпадает с `inbox_event.source` (URN) | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:260-262` 🟡 |
| `identity_mapping` ↔ `identity_feature` | 1 : N по ключу записи `(source, source_type, source_id)` | логическая, без FK | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:38-42` 🟡 |
| `identity_feature` → `identity_candidate` | N : M; кандидат — пара ключей с общими признаками | логическая | `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql:21-35` 🟡 |
| `identity_mapping` ↔ `approved_crosswalk` | пара ключей M : N; оба конца получают общий `gid` | логическая; пара хранится в нормализованном порядке `left < right`, CHECK нет | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:14-26` 🟢 |
| `identity_mapping.gid` ↔ `property_assertion.gid` ↔ `source_conflict.gid` | N : 1 по `gid` | логическая: `uuid` без FK; индексы `ix_identity_mapping_gid`, `ix_property_assertion_gid` | `V2:12`, `V5:19` 🟢 |
| `source_conflict` → `property_assertion` | расхождение собирается из двух утверждений: `dissent_*` и `master_*` | логическая; `source_conflict` хранит значения копией | `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql:23-39` 🟡 |
| `admin_audit` | самостоятельная; `replay_id` связывает с `ReplayResult.replayId` (в БД других таблиц с таким полем нет) | — | `ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql:2-13` 🟢 |

Все пути в таблице относительны к `ingestion/identity-resolution/src/main/resources/db/migration/` (V2, V4, V5), `ingestion/event-journal/src/main/resources/db/migration/` (V1) и `ingestion/ingestion-service/src/main/resources/db/migration/` (V3), если не указан полный. Полиморфных связей и таблиц-связок нет.

Следствия отсутствия FK 🟡: ссылочную целостность между `identity_*`, `property_assertion`, `source_conflict` и графом держит приложение; удаления строк в этих таблицах нет, кроме замены набора записи (`DELETE` + `INSERT` в одной транзакции), поэтому «висячих» ссылок на практике не возникает.

## 2. Neo4j

Кардинальности и допустимые концы задаёт `RelationType` (`contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/relation/RelationType.java:13-27`); проверка учитывает подтипы (`RUNS_ON → VirtualMachine` проходит через `ComputeInstance`, `NodeLabel.isA`, `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/NodeLabel.java:45-52`). Кардинальность в самой БД не ограничена: constraint на связи в Community нет, множественность в таблице — по смыслу домена и данным заглушек 🟡.

| Связь | От → к | Temporal | Свойства |
|---|---|---|---|
| `USES_SOLUTION` | `ITSystem` → `Solution` | да | — |
| `USES_PLATFORM` | `Solution` → `Platform` | да | — |
| `DECOMPOSED_INTO` | `ITSystem` → `Service` | да | — |
| `IMPLEMENTED_IN` | `Service` → `Repository` | да | — |
| `HAS_DEPLOYMENT` | `Service` → `Deployment` | да | — |
| `IN_ENVIRONMENT` | `Deployment` → `Environment` | да | — |
| `RUNS_ON` | `Deployment` → `ComputeInstance` \| `Namespace` | да | — |
| `DEPLOYED_FROM` | `Deployment` → `Repository` | да | `commit` (identity property: смена закрывает старую связь и открывает новую) |
| `PART_OF` | `Namespace` → `KubernetesCluster` | нет | — |
| `HOSTED_ON` | `VirtualMachine` → `PhysicalServer` | нет | — |
| `DEPENDS_ON` | `Service` → `Service` | да | `kind`, `protocol`, `criticality` |
| `OWNED_BY` | `ITSystem` \| `Service` → `Team` | да | — |
| `ASSERTS` | `SourceRecord` → любой канонический | нет | `confidence`, `authority`, `conflicts` |
| `OWNS_RECORD` | `SourceSystem` → `SourceRecord` | нет | — |
| `PROCESSED` | `SyncRun` → `SourceRecord` | нет | — |
| `DEFERS` (вне `RelationType`) | `SourceRecord` → `PendingRelation` | нет | — |

Первые 15 строк — по `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/relation/RelationType.java:13-27` 🟢; `DEFERS` — служебная связь, которой нет в enum: проектор создаёт её сам (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/PendingRelations.java`, основной словарь §Q.4.2) 🟢. Подтипы `VirtualMachine`, `PhysicalServer` — дополнительные метки на узле с корневой меткой `ComputeInstance`, а не отдельные узлы; ограничение `gid` стоит на каждой из трёх меток (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-46`) 🟢.

## 3. Сквозные связи (PostgreSQL ↔ Neo4j ↔ S3)

См. [`data-dictionary.md`, §6](data-dictionary.md#6-сквозная-карта-ключей). Единственная ссылка «наружу» из реляционной части — `inbox_event.payload_key` / `dlq_entry.payload_key` в S3; из графа — `SourceRecord.contentHash` (тот же SHA-256). Напрямую graph ↔ PostgreSQL по FK связаны не бывают.
