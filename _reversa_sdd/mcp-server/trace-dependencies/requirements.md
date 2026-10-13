# trace-dependencies: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. ADR: [0012](../../../docs/adr/0012-trace-dependencies-impact-mode.md).

## Visão Geral

Tool `trace_dependencies` обходит граф от стартового актива по разрешённым типам связей и возвращает дедуплицированные узлы, связи и пути. Режим `trace` (по умолчанию) идёт вниз (по стрелкам) или вверх (против стрелок). Режим `impact` идёт только вверх против стрелок `RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO, DEPENDS_ON` до затронутых `Service`/`ITSystem` (`affected`) и может ограничиваться окружением (`environment`). Каждый шаг объясним: связь несёт источник утверждения, время получения и признак `stale`, узел несёт `stale` и маркеры конфликтов.

## Regras de Negócio

- `gid` — UUID (нормализуется), иначе `gid must be a UUID` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:111, 165-172).
- `mode`: пусто → `trace`; значение приводится `strip().toLowerCase(ROOT)`; допустимы `trace`, `impact`, иначе `mode must be trace or impact` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:112-116).
- `impact`: `direction` допустим только пустой или `upstream`, иначе `direction must be upstream for mode impact`; фактическое направление — `upstream` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:118-123).
- `trace`: `direction` по умолчанию `downstream`; допустимы `upstream`/`downstream`; иначе `direction must be upstream or downstream` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:124-127).
- `environment`: пусто → без фильтра; задан вне `impact` → `environment is supported only for mode impact`; длиннее 128 символов → `environment is too long` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:35, 129-133).
- Глубина: `maxDepth` по умолчанию = `limits.maxDepth` (6); вне `1..limits.maxDepth` → `maxDepth must be in 1..6` (отклонение, не зажатие) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:134-137).
- Глубина подставляется в текст Cypher числом после проверки диапазона (граница variable-length пути не параметризуется); обход без верхней границы запрещён: шаблон допускает только `{maxDepth}` или литерал ≤ потолка 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:40, 84-96, 125-138; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:34-38).
- `relationTypes`: пусто → весь allowlist режима; иначе каждый тип обязан входить в allowlist режима, иначе `Unknown relation type; allowed: ...` (значение не эхоится) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:138, 174-185; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-19, 97-99).
- Allowlist `trace`: `DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON, HOSTED_ON, OWNED_BY, DEPLOYED_FROM`; `impact`: `RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO, DEPENDS_ON` 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-19, 97-99).
- Шаблон выбирается: `impact` → `trace_impact`; иначе `downstream` → `trace_downstream`, `upstream` → `trace_upstream` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:140-143).
- В пути участвуют только открытые связи (`validTo IS NULL`) и текущие узлы (`isCurrent = true`) 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:28-33).
- Если обход ничего не вернул, а `get_asset` не находит узел → `asset not found`; пустой обход у существующего узла — не ошибка 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:154-163).
- Пути не сортируются в Cypher; сортировка в Java: по числу узлов, затем лексикографически по `gid` узлов 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:37-50, 200-201).
- Узлы и связи дедуплицируются; связь определяется ключом `(type, from, to)`; `paths` ссылаются на них по `gid`/ключу 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:196-231; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:105-126).
- `stale` узла: `lastSeenAt` не задан или строго старше `now − stale-after` (ровно на пороге ещё свежо); `stale` связи: запись-источник не найдена / неактивна / `sourceFetchedAt` старше порога 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:187-190, 207-229; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:259-295).
- `affected` (только `impact`) — уникальные `gid` концов путей в порядке первого появления; в `trace` пусто 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:232-234; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:231-258).
- `environment` в `impact`: каждый `Deployment` на пути должен иметь открытую `IN_ENVIRONMENT` на `Environment` с этим `code`; путь без `Deployment` фильтр проходит 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:104-112; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:285-318).
- `truncated=true`, если путей больше `max-paths` либо превышен бюджет ответа; состав путей между вызовами тогда может отличаться 🟡 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java:22-23; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:173-182, 375-393).
- Ограничение: связь, утверждённая записью без узла, не несёт `sourceFetchedAt` и всегда `stale` 🟡 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:98-100).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `trace` вниз/вверх по allowlist, глубина 1..6 | Must | AC-01, AC-02 |
| RF-02 | `impact` вверх до `Service`/`ITSystem` с `affected` | Must | AC-03 |
| RF-03 | Фильтр `environment` в `impact` | Should | AC-04 |
| RF-04 | Объяснимость шага: источник, свежесть, конфликты | Must | AC-05 |
| RF-05 | Отклонение невалидных аргументов без эха и без запроса к графу | Must | AC-06 |
| RF-06 | `truncated` при превышении бюджета путей | Must | AC-07 |
| RF-07 | Доступ только со scope `architecture.read` | Must | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Тип связи только из allowlist; в Cypher идёт параметр `$relationTypes`, глубина — проверенный `int` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:138-149; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138 | 🟢 |
| Безопасность | Тексты ошибок не содержат ввода | access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:147-172 | 🟢 |
| Производительность | Бюджет: ≤ 50 путей, ≤ 500 узлов, 5 с, 512 КБ; плотный граф укладывается в таймаут и помечается `truncated` | access/mcp-server/src/main/resources/application.yml:45-50; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:375-393 | 🟢 |
| Производительность | Latency в пределах бюджета (S8) | platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:392 | 🟡 |
| Корректность | Циклы `DEPENDS_ON` не зацикливают обход и не повторяют связь в пути | platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:164-172 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (TraceDependenciesToolTest.defaultsAreDownstreamFullAllowlistAndConfiguredDepth; TraceDependenciesIT.downstreamFollowsDecompositionDeploymentRunsOnAndHostedOn; F3AcceptanceIT.s2_traceDownstreamFromPaymentsCore)
Дано ITSystem с декомпозицией на Service, развёртыванием и хостингом
Когда trace_dependencies gid=<UUID> без остальных аргументов
Тогда direction=downstream, глубина = 6, relationTypes = весь allowlist, пути по возрастанию длины

# AC-02 (TraceDependenciesToolTest.upstreamUsesUpstreamTemplateAndRequestedDepthAndTypes; TraceDependenciesIT.upstreamGoesAgainstArrowsFromComputeInstanceToSystem, depthBoundsTheTraversal, relationTypesFilterNarrowsTheTraversal; F3AcceptanceIT.s3_traceUpstreamFromVmLimitedByTypeAndDepth)
Дано direction=upstream, maxDepth=2, relationTypes=["RUNS_ON"]
Когда trace_dependencies
Тогда используется шаблон trace_upstream, бюджет глубины 2, обход ограничен указанным типом

# AC-03 (TraceDependenciesToolTest.impactUsesImpactTemplateWithEnvironmentAndImpactAllowlist, impactSortsPathsAndListsAffectedWithoutDuplicates; TraceDependenciesIT.stoppingVmReachesServicesAndSystemsWithStepProvenance; F3AcceptanceIT.s4_impactFromVmReturnsExplainablePaths)
Дано mode=impact от виртуальной машины
Когда trace_dependencies
Тогда direction=upstream, пути заканчиваются Service/ITSystem, affected содержит их gid без дублей, у шагов есть assertedBy* и sourceFetchedAt
Дано mode=trace
Тогда affected пуст

# AC-04 (TraceDependenciesIT.environmentFilterCutsDeploymentsOfOtherEnvironments, pathWithoutDeploymentPassesEnvironmentFilter, environmentFilterIgnoresClosedEnvironmentLink; F3AcceptanceIT.s5_impactEnvironmentFilter)
Дано mode=impact, environment="PROD"
Когда trace_dependencies
Тогда пути через Deployment чужого окружения отсекаются; путь без Deployment проходит; закрытая связь окружения не учитывается

# AC-05 (TraceDependenciesToolTest.nodeStalenessBoundaries, relationStalenessCoversMissingInactiveOldAndFresh, conflictMarkersAreCarriedOnTheNode; TraceDependenciesIT.freshnessConflictsAndInactiveSourceAreProjected)
Дано stale-after=P7D и узлы/связи разной свежести
Когда trace_dependencies
Тогда узел ровно на пороге свеж, старше порога stale, без lastSeenAt stale; связь с неактивной или отсутствующей записью stale; конфликты узла перечислены без значений

# AC-06 (TraceDependenciesToolTest.rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput, rejectsBadArgumentsForImpact; TraceDependenciesMcpTest.invalidInputIsReportedAsToolErrorWithoutEchoingInput, impactValidationErrorsAreToolErrorsWithoutEchoingInput, unknownModeIsReportedAsToolError; F3AcceptanceIT.s6_invalidInputIsToolErrorWithoutStacktrace)
Дано gid не UUID, либо maxDepth = 0 или 7, либо relationTypes=["ASSERTS"] / ["RUNS_ON]-() DETACH DELETE n"], либо mode="bogus", либо environment при mode=trace
Когда trace_dependencies
Тогда isError=true, HTTP 200, значение ввода в тексте нет, GraphQueries.execute не вызывался
Дано неизвестный UUID актива
Тогда "asset not found" (после проверки GET_ASSET), без gid в тексте

# AC-07 (TraceDependenciesMcpTest.callWithScopeReturnsPathsAndTruncated; TraceDependenciesIT.maxPathsBelowPathCountIsTruncated, denseGraphImpactIsTruncatedWithinTimeout, denseGraphUpstreamTraceIsTruncatedWithinTimeout)
Дано путей больше max-paths
Когда trace_dependencies
Тогда truncated=true, число путей ≤ max-paths, запрос завершился в пределах таймаута

# AC-08 (TraceDependenciesMcpTest.callWithoutRequiredScopeIsDenied, impactWithoutRequiredScopeIsDenied; F3AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied)
Дано токен без architecture.read либо без токена
Когда tools/call trace_dependencies (trace или impact)
Тогда 403 либо 401
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-02, RF-04..RF-07 | Must | Анализ зависимостей и влияния (F3) |
| RF-03 | Should | Сужение impact окружением |
| Обход без верхней границы глубины | Won't | Запрещён правилами проекта |
| Детерминированный состав путей при `truncated` | Won't | Нет порядка в Cypher (🟡) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java, access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java | tool и ответ | 🟢 |
| access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java | шаблоны `trace_downstream`, `trace_upstream`, `trace_impact` | 🟢 |
| access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java, access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java | подстановка и проверка глубины | 🟢 |
