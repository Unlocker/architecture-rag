# acceptance-mcp-f1-f4: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты (свойства MCP, claims): [`../contracts.md`](../contracts.md) §3–§4. Заглушки: [`../../source-stubs/requirements.md`](../../source-stubs/requirements.md). Сервер: [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md), права: [`../../permissions.md`](../../permissions.md).

## Visão Geral

Четыре приёмочных класса `F1AcceptanceIT`…`F4AcceptanceIT` доказывают критерии готовности PoC 5, 7 и 9: каждый из пяти MCP-tools (`search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies` в режимах trace и impact, `explain_provenance`) вызывается по Streamable HTTP клиентом MCP с токеном `architecture.read` поверх графа, загруженного настоящими адаптерами из заглушек и настоящим диспетчером ingestion; запрос без токена получает 401, с чужим scope — 403 (`docs/00-vision.md:802`, `docs/01-development-order.md:40-43`). Это сквозные тесты «источник → адаптер → журнал → проектор → Neo4j → MCP» без подмен слоёв, кроме JWT ingestion (HS256-декодер) и ручной вставки отдельных рёбер в F2 🟢.

## Regras de Negócio

- **Каркас.** Контейнеры стартуют в `static {}`, класс `@TestInstance(PER_CLASS)` с `@TestMethodOrder(OrderAnnotation)`; в `@BeforeAll` четыре адаптера над `StubSourceServer` выполняют полный snapshot (`pollOnce().outcome() == SNAPSHOT_COMPLETED`), затем ожидание пустой очереди и нуля `QUARANTINED`, затем индексы Neo4j `ONLINE`, затем `CREATE USER mcp-reader`, затем второе приложение `McpServerApplication` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:141-185, 191-220`).
- MCP-приложение: протокол `STATELESS`, endpoint `/mcp`, `tool-scopes.<tool> = architecture.read` для tools класса и `ping`, лимиты `6 / 500 / 50 / 5s / 512KB`, читающая учётка `mcp-reader` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:192-218`).
- Клиент — `McpSyncClient` на `HttpClientStreamableHttpTransport`, заголовок `Authorization: Bearer <McpTestJwt.token("architecture.read")>`, таймаут запроса 60 с; ответ tool — JSON в `TextContent`, `isError = false` для успеха 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:363-385`).
- **F1** (`search_assets`, `get_asset`): полнотекстовый поиск по имени отдаёт `ITSystem Payments Core` первым с `matchType = FULLTEXT`, положительным `score`, `sources ∋ EAM`, `lastSeenAt` в ISO-8601; поиск по `sourceId` `EAM-1042` — `matchType = EXACT` и тот же `gid`; фильтры `types=[Service]` и `environment=prod`; `limit=1` при ≥ 2 попаданиях → `truncated = true`; карточка со связями (`includeRelations=true`) содержит источник `EAM` `sourceId 5101` с `authority = MASTER`, связи `DECOMPOSED_INTO` (OUT → Service `payments-api`) и `OWNED_BY` (→ `Payments Team`); `gid` соседа пригоден для следующего `get_asset` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:250-333`).
- **F2** (`find_runtime_footprint`): для `Payments Core` в окружении `prod` — сервис `payments-api` → ровно одно развёртывание (`name payments-api`, версия `1.4.2`, источник `DEPLOYMAP`, `hasDeployment` с источником и `sourceId`) → одна VM `vm-pay-01.prod.example.org` (тип `VirtualMachine`, источник `CMDB`, `runsOn.sourceId` = развёртывание), `stale = false`, `warnings` пусто, `truncated = false`; окружение без развёртываний сохраняет сервис с пустым списком; смена deploy map в заглушке (новая версия `1.5.0`, хост `vm-pay-02`) после `pollOnce` видна в ответе (`lastSeenAt` позже прежнего), замещённый хост исчезает; удаление развёртывания убирает его, сервис остаётся; commit и репозиторий приходят из открытого `DEPLOYED_FROM`, закрытое ребро не отдаётся 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:255-396`).
- **F3** (`trace_dependencies`): схема tool содержит `gid`, `mode`, `environment`, `direction`, `maxDepth`; trace вниз от `Payments Core` доходит до VM (путь `DECOMPOSED_INTO → HAS_DEPLOYMENT → RUNS_ON`) и до `ledger-api` (путь заканчивается `DEPENDS_ON`), `HOSTED_ON` не попадает; trace вверх от VM с `relationTypes=[RUNS_ON]`, `maxDepth=1` даёт один путь из двух узлов; impact от VM затрагивает `payments-api` и `Payments Core`, но не `ledger-api`, цепочка до системы `RUNS_ON → HAS_DEPLOYMENT → DECOMPOSED_INTO`, каждый шаг пути несёт источник, тип и id утверждающей записи, узлы — `lastSeenAt`; фильтр окружения `prod` не меняет затронутых, `no-such-env` даёт пусто 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:265-361`).
- **F3, невалидный ввод:** шесть отказов tool-ошибкой без стектрейса: неизвестный gid (`asset not found`), не-UUID (`gid must be a UUID`), `mode=foo` (`mode must be trace or impact`), `impact` + `direction=downstream` (`direction must be upstream for mode impact`), `maxDepth=7` (`maxDepth must be in 1..6`), `environment` в режиме trace (`environment is supported only for mode impact`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:365-375`).
- **F3, задержки:** холодный impact (измерен в s4) ≤ 15 с; p95 по 10 тёплым вызовам trace и impact ≤ 5 с 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:248-250, 391-417`).
- **F4** (`explain_provenance`): подготовка — `@Primary EventProcessor` с `ScmItSystemMapper`, два актива связаны через `POST /admin/crosswalks` (обязательно до первого события любой из записей), критичность `HIGH` (EAM) и `LOW` (SCM); провенанс двух источников: EAM `MASTER` (`authoritative`, `sourceId 9101`), SCM `SUPPLEMENTARY`, `conflictState = OPEN`, `conflictProperties ∋ criticality`; значение мастера (`HIGH`) читается через `get_asset`; фильтр `property=criticality` оставляет конфликт, `property=name` — `NONE` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:189-250, 328-371`).
- **F4, tombstone:** удаление SCM-записи (supplementary) через инкрементальный путь оставляет актив текущим (`isCurrent`, `deletedAt = null`), снимает конфликт (`NONE`) и находится поиском; tombstone **мастера** (объект eam-api архивирован, reconcile после snapshot) закрывает актив (`isCurrent = false`, `deletedAt` задан) при активной записи SCM — правило «закрыт, когда нет активных MASTER-утверждений» 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:375-401, 432-448`); 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 4)] формулировка ADR 0020 шире — Q-05.
- **Ошибки tools** не содержат стектрейса и эха значения: `isError = true`, текст не пуст, не содержит `"\tat "` и `"Exception"` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:337-346`).
- **Доступ:** сырой `POST /mcp` с `tools/call` без токена → 401; со scope `architecture.other` → 403 с `WWW-Authenticate`, содержащим `insufficient_scope`; проверен tool класса: `search_assets` (F1), `find_runtime_footprint` (F2), `trace_dependencies` (F3), `explain_provenance` (F4) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:349-359`).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 11); ответ владельца: вопросы 14] Не проверено: `stale = true` (Q-06); неверный audience/issuer, просроченный и подделанный токен, `tools/list` без scope, отдельно `get_asset` и `ping` (Q-01, Q-12); полный набор `tool-scopes` из `application.yml` (Q-25).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `search_assets`/`get_asset` отвечают по загруженному графу (F1) | Must | AC-01 |
| RF-02 | `find_runtime_footprint` отражает смену deploy map, замену хоста и удаление (F2) | Must | AC-02 |
| RF-03 | `trace_dependencies` trace и impact с объяснимыми путями, отказ на невалидный ввод (F3) | Must | AC-03, AC-04 |
| RF-04 | `explain_provenance` показывает мастер, конфликт и tombstone (F4) | Must | AC-05 |
| RF-05 | Каждый tool закрыт scope `architecture.read`: 401 без токена, 403 при чужом scope | Must | AC-06 |
| RF-06 | Задержки tools в пределах бюджета | Should | AC-07 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Desempenho | Холодный impact ≤ 15 с, p95 trace/impact ≤ 5 с (n=10) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:248-250, 412-414` | 🟢 (🟡 p95 при n=10 равен максимуму: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:497-500`) |
| Segurança | Токен MCP проверяется по подписи, issuer и audience через JWKS (`McpTestJwt`) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:203-206` | 🟢 |
| Segurança | Ошибки без стектрейса и без значения ввода | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:337-346`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:468-475` | 🟢 |
| Надёжность | Тесты упорядочены `@Order`; одиночный метод не запускается | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:78-79, 392-396` | 🟢 |
| Эксплуатация | Каркас дублируется в четырёх классах | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:363-427` | 🟡 (Q-21) |

## Критерии приёмки

```gherkin
# AC-01: F1, позитив и отказ (F1AcceptanceIT.s2_searchByNameIsFulltext, s3_searchBySourceIdIsExact, s5_limitTruncates, s6_getAssetCardWithRelations, s7_unknownGidIsToolErrorWithoutStacktrace)
Дано граф загружен из заглушек, MCP принимает токен architecture.read
Когда search_assets(query="Payments Core")
Тогда первый элемент — ITSystem Payments Core, matchType=FULLTEXT, sources содержит EAM, truncated=false
Когда search_assets(query="EAM-1042")
Тогда первый элемент имеет matchType=EXACT и gid Payments Core
Когда search_assets(query="api", limit=1) при ≥ 2 попаданиях
Тогда 1 элемент и truncated=true
Когда get_asset(gid, includeRelations=true)
Тогда источник EAM 5101 MASTER active, связи DECOMPOSED_INTO (payments-api) и OWNED_BY (Payments Team)
Когда get_asset с неизвестным gid
Тогда isError=true, текст без "\tat " и "Exception"

# AC-02: F2 (F2AcceptanceIT.s2_footprintOfSeededProd, s3_environmentWithoutDeploymentsKeepsServiceWithEmptyDeployments, s4_deployMapChangeIsVisibleAfterSync, s4b_replacedHostIsNoLongerReturned, s5_deletedDeploymentDisappearsButServiceStays, s6_invalidAndUnknownGidAreToolErrorsWithoutEchoOrStacktrace)
Дано footprint(Payments Core, prod)
Тогда сервис payments-api, одно развёртывание 1.4.2 (DEPLOYMAP), одна VM vm-pay-01 (CMDB), stale=false
Когда окружение test без развёртываний
Тогда сервис остаётся, deployments пуст
Когда в deploy map сменены версия 1.5.0 и хост vm-pay-02, затем pollOnce
Тогда ответ показывает 1.5.0 и vm-pay-02, lastSeenAt позже, прежний хост не возвращается
Когда развёртывание удалено в заглушке и pollOnce
Тогда deployments пуст, сервис остаётся
Когда gid невалиден или неизвестен
Тогда tool-ошибка без эха значения и стектрейса

# AC-03: F3, пути и фильтры (F3AcceptanceIT.s2_traceDownstreamFromPaymentsCore, s3_traceUpstreamFromVmLimitedByTypeAndDepth, s4_impactFromVmReturnsExplainablePaths, s5_impactEnvironmentFilter)
Дано trace вниз от Payments Core
Тогда путь к VM — DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON; путь к ledger-api заканчивается DEPENDS_ON; HOSTED_ON отсутствует; у каждой связи assertedBySource не пуст
Дано impact от VM
Тогда affected содержит payments-api и Payments Core, не содержит ledger-api; цепочка RUNS_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO; у каждого шага есть источник, тип и id утверждения
Когда environment="no-such-env"
Тогда paths и affected пусты

# AC-04: F3, невалидный ввод (F3AcceptanceIT.s6_invalidInputIsToolErrorWithoutStacktrace)
Когда maxDepth=7
Тогда tool-ошибка "maxDepth must be in 1..6" (отказ, не clamp)
Когда mode=impact и direction=downstream
Тогда tool-ошибка "direction must be upstream for mode impact"
Когда gid="not-a-uuid"
Тогда tool-ошибка "gid must be a UUID"

# AC-05: F4 (F4AcceptanceIT.s2_assetFromTwoSourcesShowsMasterAndConflict, s3_propertyFilterKeepsOnlyThatPropertyConflict, s4_tombstoneInOneSourceKeepsAssetCurrent, s7_tombstoneOfMasterClosesAssetEvenWithActiveSupplementary, s5_unknownGidIsToolErrorWithoutStacktrace)
Дано актив из EAM (критичность HIGH) и SCM (LOW), связанные crosswalk
Тогда EAM — MASTER authoritative, SCM — SUPPLEMENTARY, conflictState=OPEN, у SCM conflictProperties содержит criticality
Когда property="name"
Тогда conflictState=NONE
Когда запись SCM удалена (инкрементальный DELETE)
Тогда запись неактивна, актив isCurrent, conflictState=NONE, актив находится поиском
Когда объект EAM архивирован и выполнен полный snapshot
Тогда isCurrent=false, deletedAt задан, запись SCM остаётся active
Когда gid неизвестен
Тогда tool-ошибка "asset not found" без стектрейса

# AC-06: доступ (F1AcceptanceIT.s8_accessWithoutScopeOrTokenIsDenied, F2AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied, F3AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied, F4AcceptanceIT.s6_accessWithoutScopeOrTokenIsDenied)
Когда POST /mcp tools/call без токена
Тогда 401
Когда POST /mcp tools/call с токеном scope=architecture.other
Тогда 403 и WWW-Authenticate содержит insufficient_scope

# AC-07: задержки (F3AcceptanceIT.s8_latencyWithinBudget)
Дано холодный impact измерен в s4, затем 10 тёплых вызовов trace и impact
Тогда холодный ≤ 15 с, p95 trace ≤ 5 с, p95 impact ≤ 5 с

# AC-08 (непокрыто) 🔴
Дано токен с чужим audience, просроченный или подделанный; либо stale-данные старше порога
Когда вызывается tool
Тогда ожидается 401 либо stale=true; тестов нет (Q-01, Q-06)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Критерий PoC 5 и 7 |
| RF-06 | Should | Критерий 9, пороги ориентировочные |
| `stale=true`, audience/exp, `tools/list` без scope | Won't | Не реализовано (🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 11); ответ владельца: вопросы 14] Q-01, Q-06, Q-12) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java` | `F1AcceptanceIT` (8) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java` | `F2AcceptanceIT` (9) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java` | `F3AcceptanceIT` (8) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java` | `F4AcceptanceIT` (7) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/McpTestJwt.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiSupport.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ScmItSystemMapper.java` | помощники | 🟢 |
