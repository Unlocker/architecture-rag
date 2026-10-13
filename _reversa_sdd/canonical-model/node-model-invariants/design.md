# node-model-invariants — Дизайн

> Таблица полей и проверок каждого типа — в [`../design.md`](../design.md), раздел «Узлы» и «Provenance». Здесь — механика и решения.

## Интерфейс

| Символ | Роль |
|---|---|
| `NodeData` (sealed) + `label()` | общий тип данных узла; метка Neo4j определяется самим record (`CM/node/NodeData.java:9-13`) |
| `CanonicalNode<T extends NodeData>(UUID gid, T data, Lifecycle lifecycle)` | хранимый узел (`CM/node/CanonicalNode.java:7`) |
| `Lifecycle(firstSeenAt, lastSeenAt, deletedAt, isCurrent)` | интервал жизни узла (`CM/node/Lifecycle.java:12`) |
| `Invariants.requireText / optionalText / requireNotAfter` | общие проверки (`CM/Invariants.java:21-42`) |

## Основной поток

1. Нормализатор вызывает конструктор record'а; compact constructor последовательно применяет `requireText` / `optionalText` / `Objects.requireNonNull` и специальные проверки (см. таблицу в `../design.md`).
2. `Deployment` дополнительно вызывает `GitUrls.normalize(repositoryUrl)` и сравнивает с исходным значением: нормализованное значение должно совпасть (`CM/node/Deployment.java:30-32`). Для мусорного URL `GitUrls` бросит собственное IAE раньше, чем сравнение (🟡: сообщение будет от `GitUrls`, а не `"repositoryUrl must be a normalized git url"`).
3. `ComputeInstance.label()` — `switch` по `ComputeKind` без `default` (`CM/node/ComputeInstance.java:35-41`).
4. `Lifecycle`: `Objects.requireNonNull` ×2, `Invariants.requireNotAfter(first, last, ...)`, затем `isCurrent != (deletedAt == null)` → IAE `"isCurrent must be true exactly when deletedAt is null"` (`CM/node/Lifecycle.java:14-21`).

## Альтернативные потоки

- **Старый код с короткими конструкторами:** `ITSystem(name,status,criticality,description)` и 6-арг. вариант делегируют в 7-арг., подставляя `null` (`CM/node/ITSystem.java:28-36`); `Solution(name,lifecycle,description)` — в 4-арг. (`CM/node/Solution.java:24-26`).
- **Нетипизированный CMDB-узел:** `ComputeKind.UNSPECIFIED` → метка `COMPUTE_INSTANCE`, `hypervisorRef` и `serialNumber` запрещены (`CM/node/ComputeInstance.java:26-31`).

## Зависимости

`Invariants`, `GitUrls` (только `Deployment`), JDK `Instant`/`UUID`/`regex`. Потребители: `ingestion/normalizer` (производство), `graph-projector` (запись свойств через `NodeProperties`), `graph-query-core` 🟡.

## Решения

| Решение | Evidence | Уверенность |
|---|---|---|
| Узлы = records с compact constructors | `CM/node/*.java` | 🟢 |
| `null` = «не передано»; пустая строка = ошибка | `CM/Invariants.java:29-35`, `CM/node/Repository.java:6` | 🟢 |
| `Namespace`, `KubernetesCluster` смоделированы, но не наполняются в PoC | `CM/node/KubernetesCluster.java:6`, ADR 0011 | 🟢 |
| `eamSystemId` — целочисленный `id` EAM, а не `eam_id` (ADR 0024) | `CM/node/Deployment.java:14-15` | 🟢 |
| `Solution` назван так же, как `Lifecycle` в Javadoc — предупреждение о путанице | `CM/node/Solution.java:8-9` | 🟢 |

## Риски

- 🟡 `ITSystem.criticality`/`criticalityRaw` взаимоисключение не проверяется (Javadoc: «set instead of»).
- 🟡 `Repository.url` не проверяется, поэтому в графе могут оказаться ненормализованные URL; сопоставление идёт по `GitUrls.normalize` на стороне проектора/lookup (`ingestion/normalizer/.../DeploymentLookup.java:38-40`).
- 🟡 `SourceRecord.sourceVersion` — произвольный непустой текст; числовой формат (`SourceVersion` в `event-schemas`) здесь не проверяется.
- 🟡 В модуле две сущности с именем `SourceSystem` (record здесь, enum в `contracts/source-spi`) и два набора кодов (`DEPLOYMAP` против `DEPLOY_MAP`); преобразование вне `contracts/*` (`code-analysis.md` A5).
