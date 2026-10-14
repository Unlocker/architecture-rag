# find-runtime-footprint: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. ADR: [0009](../../../docs/adr/0009-deployment-from-deploymap.md), [0029](../../../docs/adr/0029-helm-chart-system-repo-commit.md).

## Visão Geral

Tool `find_runtime_footprint` отвечает на вопрос «где развёрнута система»: по `gid` узла `ITSystem` (и, при желании, коду окружения) возвращает сервисы системы, их действующие развёртывания (`Deployment`) по окружениям и вычислительные узлы (VM, физические серверы, `ComputeInstance`) с provenance узлов и рёбер и признаком `stale`. У развёртывания отдаётся источник кода из Helm chart: `sourceCommit`, `repositoryUrl`, `repository`, `deployedFrom` (`null`, если открытой связи `DEPLOYED_FROM` нет).

## Regras de Negócio

- `systemGid` должен быть UUID; иначе `systemGid must be a UUID` (значение не эхоится) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:62-66).
- `systemGid` нормализуется: `strip`, `UUID.fromString`, `toString()` (нижний регистр); в шаблон уходит нормализованное значение; `null`, пустая строка и не-UUID → `systemGid must be a UUID` без эха ввода 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java; Q-06).
- `environment`: `null`/пустой = все окружения; иначе `strip` и точное сравнение с кодом окружения 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:67; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:13-30).
- Бюджет не задаётся: `budget = null`, исполнитель берёт потолки `QueryLimits` целиком 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:71-72; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:67).
- Нет строк (неизвестный gid, закрытый узел, не `ITSystem`) → `asset not found` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:73-75; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:259-267).
- Только действующие факты: `isCurrent`; у темпоральных связей ещё `validTo IS NULL`; закрытые `RUNS_ON`, `HOSTED_ON`, структурные связи не возвращаются; цели `RUNS_ON` ограничены меткой `ComputeInstance` (Namespace не попадает) 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:13-30; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:197-258).
- Строка шаблона — на (service, deployment, compute); сервис без подходящих deployments приходит строкой с `null` в полях deployment и в ответе даёт пустой `deployments` 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:13-30; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:191-204; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:121-136).
- Группировка: `service → deployment → compute` через `LinkedHashMap.computeIfAbsent` по `gid`, порядок строк сохраняется; дубль compute внутри deployment пропускается; физический сервер под VM кладётся в `ComputeFootprint.hostedOn` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:87-138; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:119-157).
- Несколько развёртываний одного сервиса в одном окружении — отдельные записи 🟢 (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:107-120).
- `stale` узла: `lastSeenAt` не задан или строго раньше `now − stale-after`; на каждый устаревший узел — одна строка в `warnings` вида `stale: <тип> <gid> lastSeenAt=<время>` (повтор по `gid` не добавляется) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:200-210; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:205-229).
- Источник кода развёртывания: `sourceCommit`, `repositoryUrl`, `repository{gid,name,url}`, `deployedFrom{source,sourceType,sourceId,validFrom,commit}` — из одной открытой связи `DEPLOYED_FROM` (при нескольких — с наибольшим `validFrom`, при равенстве — с меньшим `gid` репозитория); без связи — `null` 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:21-28; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:158-190; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java).
- Provenance рёбер (`hasDeployment`, `runsOn`, `hostedOn`) — `EdgeProvenance{source,sourceType,sourceId,validFrom}`; `validFrom` только у темпоральных связей 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java:94-95; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:224-231).
- Усечение: произведение (service × deployment × compute) может упереться в `max-nodes`; тогда `truncated=true`, последняя группа может быть неполной (для PoC принято) 🟡 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:21-26; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java:22).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Дерево `service → deployment → compute → host` с provenance и `stale` | Must | AC-01 |
| RF-02 | Фильтр по окружению; сервис без развёртываний сохраняется с пустым списком | Must | AC-02 |
| RF-03 | Источник кода развёртывания (`sourceCommit`, `repository`, `deployedFrom`) | Should | AC-03 |
| RF-04 | Закрытые факты и устаревшие узлы: закрытые не возвращаются, устаревшие помечаются | Must | AC-04 |
| RF-05 | Невалидный и неизвестный `systemGid` — ошибки tool без эха | Must | AC-05 |
| RF-06 | Доступ только со scope `architecture.read` | Must | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Cypher только с параметрами `systemGid`, `environment`; метки из allowlist шаблона | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:89-90 | 🟢 |
| Производительность | Холодный первый вызов укладывается в продуктовый таймаут; фильтр окружения — одно условие без `OR EXISTS`, подзапрос `COLLECT … LIMIT 1` вместо `OPTIONAL MATCH` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:21-29; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:268-277 | 🟢 |
| Disponibilidade | Stateless; повтор идемпотентен | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:46-53 | 🟢 |
| Корректность | Время приводится к UTC ISO-8601 (`OffsetDateTime.parse` → `Instant`) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:194-198 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (FindRuntimeFootprintToolTest.groupsRowsIntoServiceDeploymentComputeKeepingOrderAndHost; RuntimeFootprintIT.vmHostedOnPhysicalServerAndDeploymentOnPhysicalServerDirectly, nodeAndEdgeProvenanceIsReturnedAndInactiveSourcesAreSkipped; F2AcceptanceIT.s2_footprintOfSeededProd)
Дано ITSystem с двумя сервисами, развёртываниями в PROD/STAGE, VM на физическом сервере
Когда find_runtime_footprint systemGid=<UUID>
Тогда services содержат deployments, каждый deployment — compute (VM с hostedOn=физический сервер), у узлов sources и lastSeenAt, у рёбер provenance

# AC-02 (RuntimeFootprintIT.environmentFilterNarrowsToProdAndServiceWithoutDeploymentsIsKept, severalDeploymentsOfOneServiceInOneEnvironmentAreSeparateRows; FindRuntimeFootprintToolTest.serviceWithoutDeploymentsIsReturnedWithEmptyList; F2AcceptanceIT.s3_environmentWithoutDeploymentsKeepsServiceWithEmptyDeployments)
Дано environment="PROD"
Когда find_runtime_footprint
Тогда в ответе только развёртывания PROD, сервисы без них остаются с deployments=[], environment в ответе = "PROD"
Дано окружение без развёртываний
Тогда сервисы присутствуют с пустыми списками

# AC-03 (FindRuntimeFootprintToolTest.deploymentSourceCodeFieldsAreMappedAndAbsentForPoc0; F2AcceptanceIT.s2b_footprintReturnsDeploymentSourceCode; DeployedFromIT; DeploymentCommitReadIT)
Дано Deployment с открытой связью DEPLOYED_FROM
Когда find_runtime_footprint
Тогда sourceCommit, repositoryUrl, repository и deployedFrom заполнены
Дано формат deploymap-poc-0 (связи нет)
Тогда эти поля null

# AC-04 (RuntimeFootprintIT.closedRunsOnAndNonCurrentNodesAreExcluded, closedStructuralEdgesAreExcluded, runsOnNamespaceIsNotReturned; FindRuntimeFootprintToolTest.staleBoundaryAndNullLastSeenAtProduceFlagsAndWarnings; F2AcceptanceIT.s4_deployMapChangeIsVisibleAfterSync, s4b_replacedHostIsNoLongerReturned, s5_deletedDeploymentDisappearsButServiceStays)
Дано закрытая связь RUNS_ON, неактуальный узел, цель RUNS_ON метки Namespace
Когда find_runtime_footprint
Тогда они не возвращаются
Дано узел с lastSeenAt ровно на пороге stale-after, и узел без lastSeenAt
Тогда первый свеж, второй stale, и на stale-узел есть строка warnings

# AC-05 (FindRuntimeFootprintToolTest.rejectsNonUuidWithoutEchoingInputAndWithoutQuery, noRowsMeansAssetNotFound; FindRuntimeFootprintMcpTest.invalidSystemGidIsToolErrorWithoutEchoingInput; RuntimeFootprintIT.closedUnknownAndNonSystemGidsGiveNoRows; F2AcceptanceIT.s6_invalidAndUnknownGidAreToolErrorsWithoutEchoOrStacktrace)
Дано systemGid="SECRET-not-uuid", либо неизвестный UUID, либо gid закрытого узла / узла не ITSystem
Когда find_runtime_footprint
Тогда isError=true: "systemGid must be a UUID" либо "asset not found"; значение ввода и стек в ответе отсутствуют; для невалидного UUID граф не опрашивался

# AC-06 (FindRuntimeFootprintMcpTest.callWithoutScopeIsForbidden; McpSecurityTest.runtimeFootprintCallRequiresScope; F2AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied)
Дано токен без architecture.read либо со scope other.scope, либо без токена
Когда tools/call find_runtime_footprint
Тогда 403 либо 401
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-02, RF-04..RF-06 | Must | Основной сценарий F2 |
| RF-03 | Should | Источник кода из Helm chart (ADR 0029) |
| Нормализация `systemGid` | Must | Реализована (🟢 Q-06) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java, access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java | tool и ответ | 🟢 |
| access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java | шаблон `runtime_footprint` | 🟢 |
