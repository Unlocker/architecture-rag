# find-runtime-footprint: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. Блок-схема: [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md) («`find_runtime_footprint` — сборка»).

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `FindRuntimeFootprintTool.findRuntimeFootprint` | `(String systemGid, String environment) -> FindRuntimeFootprintResult` | `IllegalArgumentException`: `systemGid must be a UUID`, `asset not found` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:46-77 🟢 |
| `FindRuntimeFootprintTool(GraphQueries, StalenessProperties, Clock)` | конструктор | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:34-38 🟢 |
| `FindRuntimeFootprintResult` и вложенные | record (`ServiceFootprint`, `DeploymentFootprint`, `ComputeFootprint`, `HostFootprint`, `EdgeProvenance`, `RepositoryRef`, `DeployedFromRef`, `FactSource`) | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java:24-98 🟢 |
| `RuntimeTemplates.RUNTIME_FOOTPRINT` / `RUNTIME_FOOTPRINT_ID` | `QueryTemplate` (`ResultKind.NODES`), параметры `systemGid`, `environment` | ID `runtime_footprint` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:11, 30-90 🟢 |

## Fluxo Principal

1. Проверка `UUID.fromString(systemGid == null ? "" : systemGid)`; ошибка → `systemGid must be a UUID` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:62-66) 🟢.
2. `env` = `strip` либо `null`; параметры `systemGid` (как есть), `environment` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:67-70) 🟢.
3. `graph.execute("runtime_footprint", params, null)`: `null`-бюджет → потолки `QueryLimits` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:71-72; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:67) 🟢.
4. Нет строк → `asset not found` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:73-75) 🟢.
5. `assemble`: порог `now − staleAfter`; `system` берётся из первой строки, `warnings` — `LinkedHashMap` по `gid` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:79-85) 🟢.
6. По строкам: `service == null` → пропуск; сервис и deployment создаются `computeIfAbsent` по `gid` и каждый раз проверяются на `stale`; `compute == null` или уже был → пропуск; иначе строится `HostFootprint` (если есть `host`) и `ComputeFootprint` с provenance ребра `runsOn` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:87-138) 🟢.
7. `DeploymentBuilder.build` извлекает источник кода из проекции `source` (`repository`, `deployedFrom`) и ребро `hasDeployment` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:171-192, 233-251) 🟢.
8. Результат: `systemGid, systemName, systemLastSeenAt, systemStale, systemSources, environment, services, staleAfter (ISO-8601), warnings, truncated` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:139-149) 🟢.

## Fluxos Alternativos

- **Сервис без развёртываний** — строка с `deployment == null`: сервис попадает в ответ, `deployments=[]` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:102-105; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:191-204) 🟢.
- **Развёртывание без `DEPLOYED_FROM`** — `repository`, `deployedFrom` и `sourceCommit` `null` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:233-251; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:158-190) 🟢.
- **`lastSeenAt == null`** — `seen = null`, `stale = true`, строка в `warnings` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:194-210) 🟢.
- **Таймаут** — `QueryTimeoutException` (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:126-131).

## Dependências

- `GraphQueries`, `StalenessProperties`, `Clock` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:30-38).
- Модель Helm chart/репозитория и `DEPLOYED_FROM` — ADR [0029](../../../docs/adr/0029-helm-chart-system-repo-commit.md), пишет `graph-projector` ([`../../graph-projector/`](../../graph-projector/)).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Строка шаблона на тройку (service, deployment, compute); группировка в Java | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:21-26 | 🟢 |
| `budget = null` — потолки целиком | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:71 | 🟢 |
| `warnings` — по строке на устаревший узел, ключ `gid` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:204-210 | 🟢 |
| Источник кода — подзапрос `COLLECT … LIMIT 1`, а не `OPTIONAL MATCH` (не размножает строки) | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:21-29 | 🟢 |

## Estado Interno

Нет; билдеры (`ServiceBuilder`, `DeploymentBuilder`) живут один вызов (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:152-192).

## Observabilidade

Шаблон `runtime_footprint` в аудите; `warnings` отражают устаревшие узлы в самом ответе.

## Riscos e Lacunas

- 🟡 `systemGid` не нормализуется, в отличие от трёх других tools (Q-06).
- 🟡 Произведение service × deployment × compute упирается в `max-nodes` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:24-26).
- 🟡 `(Map<String, Object>) row.get("system")` и аналогичные приведения без проверки типов: форма строки целиком определяется шаблоном (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:253-256).
