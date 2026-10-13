# ingestion/graph-projector: решения

> Сокращения путей как в [`requirements.md`](requirements.md). Только явные решения, подтверждённые ADR или Javadoc кода; расхождения кода и ADR — в [`questions.md`](questions.md) Q-08. Карта ADR ↔ коммиты — [`../adrs/retroactive-map.md`](../adrs/retroactive-map.md).

| № | Решение | ADR / источник | Код | Статус |
|---|---|---|---|---|
| D-01 | Neo4j — производная read-only витрина; единственный писатель — этот модуль | [0001](../../docs/adr/0001-neo4j-derived-read-model.md), [0005](../../docs/adr/0005-readonly-mcp-by-application.md) | `GP/GraphProjector.java:35-37`; `ingestion/graph-projector/pom.xml:17-39` (нет зависимости от `access/*`) | 🟢 |
| D-02 | Идентичность `SourceRecord` — `(source, sourceType, sourceId)`, `gid` канонического узла выдаёт identity-слой до транзакции | [0003](../../docs/adr/0003-gid-and-source-record-identity.md) | `GP/GraphProjector.java:42-44, 305-308`; `GP/EventProcessor.java:587-599` | 🟢 |
| D-03 | Только функции Neo4j Community: UNIQUE и индексы, без NODE KEY и existence; `MERGE` лишь по ключу под constraint | [0004](../../docs/adr/0004-neo4j-community-constraints.md) | `GP/schema/Neo4jSchema.java:13-20`; `GP/GraphProjector.java:42-44` | 🟢 |
| D-04 | Авторитетность поля/связи по матрице; неавторитетное не затирает, а возвращается в `skipped*` | [0008](../../docs/adr/0008-authority-matrix-field-level.md) | `GP/GraphProjector.java:422-432, 559-563` | 🟢 |
| D-05 | Узлы не объединяются автоматически: `gid` не меняется кандидатами и конфликтами | [0019](../../docs/adr/0019-identity-resolution-no-auto-merge.md) | `GP/EventProcessor.java:64-71` | 🟢 |
| D-06 | Tombstone вместо hard delete; удаления из snapshot только после `snapshot-complete` | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) | `GP/GraphProjector.java:507-550`; `GP/Reconciler.java:27-57` | 🟢 (формулировки 0020 уже кода: Q-08) |
| D-07 | Структурные связи темпоральны; новая версия записи замещает свои связи; tombstone их закрывает; `HAS_DEPLOYMENT` без `validFrom` из источника открывается на время события | [0028](../../docs/adr/0028-structural-relations-temporal.md) | `GP/GraphProjector.java:566-575, 604-647`; `GP/TemporalRelationMigration.java:12-30` | 🟢 |
| D-08 | `RELATION`-конфликты `eamSystemId` (Deployment ↔ система сервиса) считаются в `EventProcessor` вне Neo4j; граф не меняется | [0030](../../docs/adr/0030-deployment-system-conflict.md) | `GP/EventProcessor.java:415-484` | 🟢 |
| D-09 | Инвариант платформы `ITSystem.platformId` ↔ `Solution.platformId`: расхождение фиксируется, граф не меняется | [0031](../../docs/adr/0031-it-system-platform-invariant.md) | `GP/EventProcessor.java:486-561` | 🟢 |
| D-10 | Журнал — event backbone: проектор читает/пишет статусы через `EventJournal`, не напрямую | [0006](../../docs/adr/0006-postgres-inbox-event-backbone.md) | `GP/EventProcessor.java:603-627` | 🟢 |
| D-11 | Отложенные связи (E1.14): неизвестный конец не вызывает `RETRYING`, связь хранится и достраивается; alias по `LookupKeys` (SCM `REPOSITORY.url`, EAM `IT_SYSTEM.eamCode`) | Javadoc `GP/GraphProjector.java:62-76` (отдельного ADR нет; кандидат К-3 в [`../adrs/candidates.md`](../adrs/candidates.md)) | `GP/PendingRelations.java:24-30`; `GP/GraphProjector.java:92-95` | 🟡 |
| D-12 | Tombstone reconciliation журналируется до записи в граф, чтобы replay/rebuild воспроизводили удаление (кандидат К-5) | Javadoc `GP/Reconciler.java:45-48` | `GP/Reconciler.java:156-182` | 🟡 |
| D-13 | Допущение одного писателя вместо распределённой блокировки отложенных связей | Javadoc `GP/GraphProjector.java:65-67` | `GP/GraphProjector.java:65-67` | 🟡 (Q-17) |
| D-14 | Свойства связей в `PendingRelation` — JSON-строка собственной реализации, без новой библиотеки (правило «новая библиотека — только по задаче») | Javadoc `GP/PropertyJson.java:9-11` | `GP/PropertyJson.java:13-20` | 🟢 |
