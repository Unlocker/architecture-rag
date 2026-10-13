# contracts/canonical-model — Решения (ADR-ссылки)

> Не новые ADR; реестр решений, видимых в коде, со ссылкой на `docs/adr/` и карту `_reversa_sdd/adrs/retroactive-map.md`. `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`.

| Решение | Код | ADR | Статус |
|---|---|---|---|
| Идентификация узла: `gid` + `SourceRecord` по `(source, sourceType, sourceId)` | `CM/provenance/SourceKey.java:8`, `CM/node/CanonicalNode.java:7` | [0003](../../docs/adr/0003-gid-and-source-record-identity.md) | 🟢 |
| Authority matrix на уровнях узел / свойство / связь, fail-closed | `CM/authority/AuthorityMatrix.java:14-26` | [0008](../../docs/adr/0008-authority-matrix-field-level.md) | 🟡 ADR фиксирует только `DEPENDS_ON` — EAM (Q-4) |
| `Deployment` приходит из deploy map | `CM/node/Deployment.java:17` | [0009](../../docs/adr/0009-deployment-from-deploymap.md) | 🟢 |
| `Namespace`/`KubernetesCluster` смоделированы, не наполняются | `CM/node/Namespace.java:5`, `CM/node/KubernetesCluster.java:6` | [0011](../../docs/adr/0011-namespace-cluster-modeled-not-populated.md) | 🟢 |
| Tombstone вместо hard delete | `CM/command/TombstoneSourceRecord.java:7-8`, `CM/node/Lifecycle.java:12` | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) | 🟢 |
| `sourceId` — открытый формат, без префикса системы; `eamSystemId` — целочисленный `id`, не `eam_id` | `CM/provenance/SourceKey.java:7`, `CM/node/Deployment.java:14-15` | [0024](../../docs/adr/0024-open-source-id-format.md) | 🟢 |
| Структурные связи темпоральны (`DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT`) | `CM/relation/RelationType.java:15-18` | [0028](../../docs/adr/0028-structural-relations-temporal.md) | 🟢 |
| `DEPLOYED_FROM {commit}` с identity-свойством; `sourceCommit` 40 hex | `CM/relation/RelationType.java:20, 49-52`, `CM/node/Deployment.java:20, 27-29` | [0029](../../docs/adr/0029-helm-chart-system-repo-commit.md) | 🟢 |
| Инвариант платформы ИТ-системы: `platformId` у `ITSystem`/`Solution`, мастер EAM | `CM/node/ITSystem.java:7-11`, `CM/node/Solution.java:7-9`, `CM/authority/AuthorityMatrix.java:95-96` | [0031](../../docs/adr/0031-it-system-platform-invariant.md) | 🟢 |
| Cypher подставляет метки/типы связей только из enum | `CM/node/NodeLabel.java:44-46`, `CM/relation/RelationType.java` | CLAUDE.md, ловушка 1; [0018](../../docs/adr/0018-domain-tools-not-arbitrary-cypher.md) | 🟡 |
| Время — `Instant` (UTC) | `CM/node/Lifecycle.java:12` | CLAUDE.md, ловушка 10 | 🟢 |
| Контракты `contracts/*` не зависят от проектных модулей | `contracts/canonical-model/pom.xml:1-16` | CLAUDE.md, раздел «Раскладка модулей и направление зависимостей» | 🟢 |
