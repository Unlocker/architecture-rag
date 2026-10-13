# query-core-read: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты (лимиты, фикстуры): [`../contracts.md`](../contracts.md) §5–§6. Код под тестом: [`../../flowcharts/graph-query-core.md`](../../flowcharts/graph-query-core.md), [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md). Сквозная приёмка тех же tools с HTTP — [`../acceptance-mcp-f1-f4/requirements.md`](../acceptance-mcp-f1-f4/requirements.md).

## Visão Geral

Семь классов вызывают `GraphQueryExecutor` (и, для `ExplainProvenanceIT`, `ExplainProvenanceTool` как Java-метод) под учёткой Neo4j `mcp-reader` на настоящем Neo4j Community без HTTP, JWT и scope. Они доказывают критерии 6 и 7 и ловушки 1, 2, 3, 11: запросы идут только по шаблонам с параметрами, глубина подставляется числом из бюджета, шаблоны с записью закрыты проверкой «read-only», сессия чтения отвергает запись на уровне БД, ответы не содержат сырых узлов, связей и путей, бюджеты строк, байтов и времени соблюдаются. Граф засевается либо настоящим `GraphProjector` (`AssetFixtures`), либо голым Cypher админ-драйвера 🟢.

## Regras de Negócio

- **Запись невозможна.** Шаблоны с `CREATE`, `SET`, `DETACH DELETE` принимаются конструктором (он в БД не ходит), но на первом `execute` падают `IllegalStateException("… not read-only")` и остаются закрытыми (второй вызов тоже бросает); узлов не прибавилось, значение `Keep.x` прежнее; сессия `READ_SESSION_CONFIG` отвергает `CREATE` серверным кодом `Neo.ClientError.Statement.AccessMode` — защита режимом сессии, а не ролями (Community: пользователь без ролей) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:78-120`).
- **Только шаблоны.** Публичный API исполнителя принимает единственный `String` — идентификатор шаблона (проверка рефлексией: `execute` с 3 параметрами); неизвестный шаблон, отсутствующий или лишний параметр и внешний `limit` отвергаются `IllegalArgumentException` до обращения к БД 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:136-158`).
- **Результат без сырых структур.** Шаблоны, возвращающие узел, связь или путь, в том числе внутри списка и `map`, запрещены: `IllegalStateException` с «forbidden» (пять форм возврата) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:122-134`).
- **Бюджет.** Лимит строк (5 из 20 → `truncated = true`), лимит байтов (100 Б), запрошенный бюджет зажимается потолком `QueryLimits`, тяжёлый запрос при таймауте 100 мс → `QueryTimeoutException` быстрее 20 с; конструктор исполнителя не обращается к БД 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:102-109, 160-211`).
- **Глубина — число из бюджета.** Плейсхолдер `{maxDepth}` подставляется валидированным числом: при глубине 2 достижимы 2 узла, при 3 — три; обход без верхней границы запрещён (проверяется unit-тестами модуля) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:213-228`). 🟡 Нижняя граница (`maxDepth = 0` или отрицательная) в IT не проверена.
- **Карточка актива (`GetAssetIT`).** Свойства, времена и активные источники с authority без `contentHash`; закрытый узел возвращается с `isCurrent = false` и неактивной записью; неизвестный gid и gid служебного `SourceRecord` дают пусто; связи — только открытые, на один уровень в обе стороны, из allowlist, закрытая (`validTo` в прошлом) не возвращается, бюджет узлов даёт `truncated` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:105-175`).
- **Поиск (`SearchAssetsIT`).** Точное совпадение `gid`/`sourceId` ранжируется выше fulltext и перечисляет только активные источники; fulltext по слову; узел из обеих веток возвращается один раз; фильтр типов; `Repository` ищется только точным совпадением; фильтр окружения идёт по открытым связям; лимит 1 → `truncated`; нетекущий узел и узел вне поисковых типов не отдаются; спецсимволы Lucene и ввод без текстовых символов не роняют запрос 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:138-255`).
- **Зависимости (`TraceDependenciesIT`).** Обход вниз и вверх по allowlist типов; цикл `DEPENDS_ON` завершается, связь в пути не повторяется; `maxPaths` усекает; `maxDepth` ограничивает; закрытая связь и нетекущий узел не проходятся; фильтр типов связей сужает обход; неизвестный gid → пусто; impact от VM затрагивает сервис и систему, не `ledger-api`; `DEPENDS_ON`-потребитель затронут, владение `OWNED_BY` не идёт; в путях провенанс шагов (`assertedBy*`, `sourceFetchedAt`, `sourceActive`); фильтр окружения отсекает чужие развёртывания, закрытая `IN_ENVIRONMENT` игнорируется, путь без `Deployment` проходит фильтр; закрытая `RUNS_ON` → пусто; на плотном графе 30×30 `DEPENDS_ON` impact и upstream-trace усекаются (`truncated = true`) в пределах таймаута 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:136-393`).
- **`DEPLOYED_FROM` в чтении (`DeploymentCommitReadIT`).** `footprint` возвращает один ряд с текущим commit, репозиторием и провенансом ребра (закрытое ребро ряд не размножает); без `DEPLOYED_FROM` ряд остаётся с `null`; `asset_relations` и `trace_downstream` видят только открытое ребро 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java:113-173`).
- **Footprint (`RuntimeFootprintIT`).** Несколько развёртываний одного сервиса — отдельные ряды; фильтр окружения сужает, сервис без развёртываний остаётся; VM на физсервере и развёртывание прямо на нём; активные источники и провенанс `HAS_DEPLOYMENT/RUNS_ON/HOSTED_ON`; `RUNS_ON` на `Namespace` не отдаётся; закрытые связи и нетекущие узлы исключены; не-система и неизвестный gid → пусто; холодный первый вызов ≤ 3 с при бюджете 5 с (UNLOCKER-216) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:106-277`).
- **Provenance (`ExplainProvenanceIT`).** Два источника: мастер первым, SCM `SUPPLEMENTARY` с `conflictProperties = [criticality]`, состояние `OPEN`; фильтр по свойству; согласные источники → `NONE`; после snapshot SCM без записи она `active = false`, актив остаётся текущим, конфликт очищен; неизвестный gid → `IllegalArgumentException("asset not found")` без эха значения 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:177-274`). 🟡 Маркер snapshot здесь не проходит путь «маркер → триггер»: `reconciler.reconcile` вызывается напрямую.
- 🟡 `GraphQueryCoreIT` и `RuntimeFootprintIT` работают на базе без констрейнтов и индексов: стоимость запросов отличается от прода (`Neo4jSchema.apply` не вызывается).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Шаблоны с записью закрыты; сессия чтения отвергает запись | Must | AC-01 |
| RF-02 | Нет произвольного Cypher; параметры и `limit` проверяются до БД | Must | AC-02 |
| RF-03 | Бюджеты строк, байтов, времени и потолок `QueryLimits` | Must | AC-03 |
| RF-04 | Глубина подставляется числом | Must | AC-04 |
| RF-05 | Карточка и связи только открытые, без `contentHash` | Must | AC-05 |
| RF-06 | Поиск: порядок ранжирования, фильтры, устойчивость к вводу | Must | AC-06 |
| RF-07 | Trace/impact: allowlist, цикл, усечение, провенанс шагов | Must | AC-07 |
| RF-08 | Footprint и чтение `DEPLOYED_FROM` | Must | AC-08 |
| RF-09 | `explain_provenance`: мастер, конфликт, неактивный источник | Must | AC-09 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Desempenho | Бюджет запроса в IT `(6, 500, 50, 5 с, 512 КиБ)`; таймаут из `archrag.it.queryTimeoutSeconds` (5 по умолчанию) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:46`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:51`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:47`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java:39` | 🟢 |
| Desempenho | Холодный первый вызов footprint ≤ 3 с; «холодность» не гарантирована (порядок методов не зафиксирован) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:268` | 🟡 |
| Desempenho | Плотный граф: усечение в пределах таймаута, время не измеряется — падение только по исключению таймаута | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:375-393` | 🟡 |
| Segurança | Ошибки без эха значения: `asset not found`; запрос с неизвестным шаблоном не доходит до БД | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:269-274` | 🟢 |
| Isolamento | `@BeforeEach MATCH (n) DETACH DELETE n` + `seed()` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:57-60` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: запись невозможна (GraphQueryCoreIT.writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed, readSessionConfigRejectsWritesAtDatabaseLevel; AdminConsoleIT.everyConsoleTemplatePassesReadOnlyExplainAndWritingTemplateIsClosed)
Дано шаблон с CREATE, SET либо DETACH DELETE
Когда исполнитель выполняет его первый раз
Тогда IllegalStateException "not read-only", второй вызов тоже отказывает, узлов не прибавилось, значения не изменены
Дано сессия READ_SESSION_CONFIG
Когда выполняется CREATE
Тогда ClientException с кодом Neo.ClientError.Statement.AccessMode

# AC-02: нет произвольного Cypher (GraphQueryCoreIT.publicApiHasNoMethodAcceptingCypher, unknownTemplateAndBadParametersFailBeforeDatabase, nodesRelationshipsAndPathsInResultAreForbidden)
Дано публичные методы GraphQueryExecutor
Тогда единственный метод со String — execute(templateId, params, budget)
Когда неизвестный шаблон, нет параметра, лишний параметр либо внешний limit
Тогда IllegalArgumentException без обращения к БД
Когда шаблон возвращает узел, связь, путь или их список
Тогда IllegalStateException "forbidden"

# AC-03: бюджет (GraphQueryCoreIT.rowBudgetTruncates, byteBudgetTruncates, requestedBudgetIsClampedToConfiguredCeiling, timeoutGivesQueryTimeoutException)
Дано 20 узлов и бюджет 5 строк
Тогда 5 строк и truncated=true; при бюджете 50 — 20 строк и truncated=false
Дано потолок max-nodes=3 и запрос 1000
Тогда 3 строки и truncated=true
Дано тяжёлый запрос и таймаут 100 мс
Тогда QueryTimeoutException быстрее 20 с

# AC-04: глубина (GraphQueryCoreIT.traversalDepthIsBoundedByMaxDepth)
Дано цепочка из четырёх узлов
Когда maxDepth=2, затем 3
Тогда достижимы узлы [1,2], затем [1,2,3]

# AC-05: карточка (GetAssetIT.cardHasPropertiesTimesAndSourcesWithAuthority, closedNodeIsReturnedNotCurrentWithInactiveRecord, unknownGidAndServiceNodeGidReturnNothing, closedRelationIsNotReturned, relationsRespectNodeBudget)
Когда get_asset по существующему gid
Тогда свойства, времена, источники с authority, без contentHash
Когда gid неизвестен либо принадлежит служебному SourceRecord
Тогда пусто
Когда связь закрыта (validTo в прошлом)
Тогда она не возвращается

# AC-06: поиск (SearchAssetsIT.exactGidMatchComesFirstAboveFulltextHits, typeFilterRestrictsResults, limitAndTruncated, nonCurrentNodeIsNotReturned, luceneSpecialCharactersDoNotFail, nonTextInputRunsOnlyExactBranches)
Когда запрос совпадает с gid или sourceId точно
Тогда точное совпадение выше fulltext, в sources только активные
Когда запрос "foo AND (bar", "a:b", "*", "[a TO b]" либо "()" (после экранирования текста нет)
Тогда запрос не падает, для "()" работают только точные ветки
Когда limit=1 при нескольких попаданиях
Тогда truncated=true

# AC-07: зависимости (TraceDependenciesIT.dependencyCycleTerminatesAndDoesNotRepeatRelationInPath, maxPathsBelowPathCountIsTruncated, closedRelationIsNotTraversed, stoppingVmReachesServicesAndSystemsWithStepProvenance, denseGraphImpactIsTruncatedWithinTimeout)
Дано цикл DEPENDS_ON
Когда выполняется trace
Тогда обход завершается, связь в пути не повторяется
Когда maxPaths ниже числа путей
Тогда truncated=true
Когда impact от VM
Тогда затронуты сервис и система, у шагов есть assertedBy* и lastSeenAt
Дано плотный граф 30x30
Тогда impact и upstream-trace усечены в пределах таймаута

# AC-08: footprint (RuntimeFootprintIT.*, DeploymentCommitReadIT.*)
Дано два развёртывания одного сервиса
Тогда два ряда
Дано развёртывание без DEPLOYED_FROM
Тогда ряд один, commit и репозиторий null
Дано закрытая RUNS_ON либо нетекущий узел
Тогда исключены

# AC-09: провенанс (ExplainProvenanceIT.twoSourcesShowMasterAndOpenCriticalityConflict, tombstonedSourceIsInactiveAndConflictIsCleared, unknownGidIsAssetNotFound)
Дано актив из EAM (мастер) и SCM
Тогда мастер первый, SCM SUPPLEMENTARY с conflictProperties=[criticality], состояние OPEN
Дано запись SCM tombstone после snapshot
Тогда запись active=false, актив текущий, конфликт очищен
Когда gid неизвестен
Тогда IllegalArgumentException "asset not found" без эха значения

# AC-10 (непокрыто) 🔴
Дано maxDepth = 0, отрицательное либо шаблон с обходом * или *1..
Тогда ожидается отказ; в IT проверен только верхний предел (maxDepth=7 в F3), запрет * — unit-тестами модуля (Q-33)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-09 | Must | Критерии PoC 6, 7; ловушки 1, 2, 3, 11 |
| Нижняя граница `maxDepth` и запрет `*` в IT | Won't | Проверяется unit-тестами `graph-query-core` (🟢 [R.8: ответ владельца, вопрос 14] AC-10, Q-33) |
| Замер времени плотного графа | Won't | Не измеряется (🟡 Q-17) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java` | 11 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java` | 6 + 13 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java` | 18 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java` | 4 + 9 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java` | 5 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AssetFixtures.java` | засев через проектор | 🟢 |
