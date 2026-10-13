# ingestion/identity-resolution: решения

| Решение | Суть | ADR | Evidence |
|---------|------|-----|----------|
| Без автоматического merge | кандидаты только записываются; единственный путь к общему `gid` — approved crosswalk, вручную | [0019](../../docs/adr/0019-identity-resolution-no-auto-merge.md) | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/package-info.java:5-12` 🟢 |
| Mapping в PostgreSQL, не в графе | rebuild графа воспроизводит `gid` | 0019, [0003](../../docs/adr/0003-gid-and-source-record-identity.md) | `.../db/migration/V2__identity_mapping.sql:1-2` 🟢 |
| Tombstone вместо удаления | `forget` удаляет только признаки; кандидаты и конфликты остаются историей | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) (по духу) | `PostgresIdentityCandidates.java:105-120`; `V5__source_conflict.sql:21-22` 🟡 |
| Конфликт связи в той же таблице | колонка `kind` в `source_conflict`, `assertRelation` | [0030](../../docs/adr/0030-deployment-system-conflict.md), [0031](../../docs/adr/0031-it-system-platform-invariant.md) | `V7__source_conflict_kind.sql:4`; `SourceConflicts.java:30-48` 🟢 |
| Числовые параметры matching (веса, 50) | в коде; ADR их не фиксирует | 0019 (пробел К-6) | `FeatureKind.java:15-18`; `PostgresIdentityCandidates.java:33` 🟡 |

Пути выше относительно `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/` (классы) и `.../src/main/resources/db/migration/` (миграции).
