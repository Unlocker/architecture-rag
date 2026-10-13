# Матрица «код ↔ спецификация»

> Генерируется по `git ls-files` (без `docs/`, `.reversa/`, `_reversa_sdd/`, lock-файлов) и по текстам specs. Покрытие: 🟢 — файл процитирован полным путём (с `:строка` или в таблице трассировки) в specs; 🟡 — упомянут только по имени класса/файла; `n/a` — specs его не упоминают (кандидат на доп. анализ). Колонка «Unit» — папка единицы своего модуля; «Ещё цитируют» — прочие папки specs, где путь встречается.

## Итог

Файлов: 549; 🟢 389; 🟡 121; n/a 39; покрытие (🟢+🟡) 93%.

| Модуль | Unit | Файлов | 🟢 | 🟡 | n/a | Покрытие |
|--------|------|-------:|---:|---:|----:|---------:|
| `access/admin-console` | `admin-console/` | 35 | 32 | 3 | 0 | 100% |
| `access/admin-ui` | `admin-ui/` | 41 | 41 | 0 | 0 | 100% |
| `access/graph-query-core` | `graph-query-core/` | 25 | 23 | 2 | 0 | 100% |
| `access/mcp-server` | `mcp-server/` | 47 | 44 | 3 | 0 | 100% |
| `adapters/adapter-core` | `adapter-core/` | 23 | 15 | 5 | 3 | 87% |
| `adapters/asset-adapter` | `asset-adapter/` | 6 | 3 | 3 | 0 | 100% |
| `adapters/deploymap-adapter` | `deploymap-adapter/` | 15 | 8 | 7 | 0 | 100% |
| `adapters/eam-adapter` | `eam-adapter/` | 37 | 6 | 11 | 20 | 46% |
| `adapters/scm-adapter` | `scm-adapter/` | 6 | 3 | 3 | 0 | 100% |
| `contracts/canonical-model` | `canonical-model/` | 46 | 8 | 37 | 1 | 98% |
| `contracts/event-schemas` | `event-schemas/` | 24 | 12 | 12 | 0 | 100% |
| `contracts/source-spi` | `source-spi/` | 12 | 11 | 1 | 0 | 100% |
| `ingestion/event-journal` | `event-journal/` | 8 | 7 | 0 | 1 | 88% |
| `ingestion/graph-projector` | `graph-projector/` | 29 | 15 | 13 | 1 | 97% |
| `ingestion/identity-resolution` | `identity-resolution/` | 28 | 18 | 6 | 4 | 86% |
| `ingestion/ingestion-service` | `ingestion-service/` | 39 | 32 | 1 | 6 | 85% |
| `ingestion/normalizer` | `normalizer/` | 22 | 6 | 14 | 2 | 91% |
| `platform/demo-launcher` | `demo-launcher/` | 9 | 9 | 0 | 0 | 100% |
| `platform/docker` | `docker/` | 25 | 25 | 0 | 0 | 100% |
| `platform/integration-tests` | `integration-tests/` | 41 | 41 | 0 | 0 | 100% |
| `platform/source-stubs` | `source-stubs/` | 18 | 18 | 0 | 0 | 100% |
| `repo-root` | `—/` | 13 | 12 | 0 | 1 | 92% |

## access/admin-console

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `access/admin-console/pom.xml` | `admin-console/` | 🟢 | `admin-ui`, `code-analysis.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/AdminConsoleApplication.java` | `admin-console/` | 🟢 | `inventory.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/ApiExceptionHandler.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java` | `admin-console/` | 🟢 | `admin-ui`, `openapi` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java` | `admin-console/` | 🟢 | `admin-ui`, `domain.md`, `graph-query-core`, `openapi` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java` | `admin-console/` | 🟢 | `admin-ui`, `openapi` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/InvalidRequestException.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/NotFoundException.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java` | `admin-console/` | 🟢 | `admin-ui`, `openapi` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/package-info.java` | `admin-console/` | 🟡 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditContext.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java` | `admin-console/` | 🟢 | `admin-ui`, `permissions.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/package-info.java` | `admin-console/` | 🟡 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java` | `admin-console/` | 🟢 | `admin-ui`, `integrations.md`, `openapi` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java` | `admin-console/` | 🟢 | `admin-ui` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java` | `admin-console/` | 🟢 | `admin-ui` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderConfiguration.java` | `admin-console/` | 🟢 | `graph-query-core`, `tech-debt.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderProperties.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReads.java` | `admin-console/` | 🟢 | `graph-query-core` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/package-info.java` | `admin-console/` | 🟡 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgConfiguration.java` | `admin-console/` | 🟢 | `integrations.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgProperties.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java` | `admin-console/` | 🟢 | `admin-ui`, `database`, `ingestion-service`, `state-machines.md` … |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Page.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java` | `admin-console/` | 🟢 | `admin-ui` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java` | `admin-console/` | 🟢 | `permissions.md` |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java` | `admin-console/` | 🟢 | `admin-ui`, `erd-complete.md`, `event-journal`, `state-machines.md` … |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java` | `admin-console/` | 🟢 | `admin-ui`, `integrations.md`, `openapi`, `permissions.md` |
| `access/admin-console/src/main/resources/application.yml` | `admin-console/` | 🟢 | `admin-ui`, `architecture.md`, `code-analysis.md`, `data-dictionary.md` … |
| `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java` | `admin-console/` | 🟢 | `admin-ui`, `code-analysis.md` |
| `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleStartupTest.java` | `admin-console/` | 🟢 | `database` |
| `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/TestJwt.java` | `admin-console/` | 🟢 |  |
| `access/admin-console/src/test/resources/config/application.yml` | `admin-console/` | 🟢 |  |

## access/admin-ui

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `access/admin-ui/.gitignore` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/jest.config.js` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/package.json` | `admin-ui/` | 🟢 | `code-analysis.md`, `dependencies.md` |
| `access/admin-ui/pom.xml` | `admin-ui/` | 🟢 | `code-analysis.md` |
| `access/admin-ui/src/App.test.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/App.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/api/ApiContext.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/api/api.test.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/api/apiClient.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/api/constants.ts` | `admin-ui/` | 🟢 | `adapter-core`, `code-analysis.md`, `mcp-server`, `tech-debt.md` … |
| `access/admin-ui/src/api/endpoints.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/api/types.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/auth/authService.test.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/auth/authService.ts` | `admin-ui/` | 🟢 | `integrations.md`, `tech-debt.md` |
| `access/admin-ui/src/components/ForbiddenScreen.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/components/GraphCanvas.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/components/Layout.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/components/common.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/declarations.d.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/hooks/useAsync.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/index.html` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/index.tsx` | `admin-ui/` | 🟢 | `inventory.md` |
| `access/admin-ui/src/pages/GraphPage.test.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/GraphPage.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/SyncPage.test.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/SyncPage.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/graph/AssetCard.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/graph/AssetSearch.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/graph/GraphSummary.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/graph/NeighborhoodView.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/sync/AuditTab.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/sync/DlqTab.tsx` | `admin-ui/` | 🟢 | `code-analysis.md` |
| `access/admin-ui/src/pages/sync/EventsTab.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/pages/sync/IdentityTab.tsx` | `admin-ui/` | 🟢 | `code-analysis.md` |
| `access/admin-ui/src/pages/sync/SourcesTab.tsx` | `admin-ui/` | 🟢 | `code-analysis.md` |
| `access/admin-ui/src/styles.css` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/testing/mockApi.tsx` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/useHashRoute.test.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/src/useHashRoute.ts` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/tsconfig.json` | `admin-ui/` | 🟢 |  |
| `access/admin-ui/webpack.config.js` | `admin-ui/` | 🟢 | `code-analysis.md` |

## access/graph-query-core

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `access/graph-query-core/pom.xml` | `graph-query-core/` | 🟢 | `canonical-model`, `code-analysis.md` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java` | `graph-query-core/` | 🟢 | `architecture.md`, `c4-components.md`, `domain.md`, `mcp-server` … |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java` | `graph-query-core/` | 🟢 | `data-dictionary.md` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java` | `graph-query-core/` | 🟢 | `admin-console`, `c4-components.md`, `domain.md`, `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java` | `graph-query-core/` | 🟢 | `c4-components.md`, `domain.md`, `mcp-server`, `permissions.md` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTimeoutException.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java` | `graph-query-core/` | 🟢 | `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java` | `graph-query-core/` | 🟢 | `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/package-info.java` | `graph-query-core/` | 🟡 |  |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java` | `graph-query-core/` | 🟢 | `admin-console`, `admin-ui`, `database`, `integrations.md` … |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java` | `graph-query-core/` | 🟢 | `admin-console`, `admin-ui` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java` | `graph-query-core/` | 🟢 | `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java` | `graph-query-core/` | 🟢 | `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java` | `graph-query-core/` | 🟢 | `domain.md`, `mcp-server` |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/package-info.java` | `graph-query-core/` | 🟡 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/ResultBudgetTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplatesTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplatesTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplatesTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQueryTest.java` | `graph-query-core/` | 🟢 |  |
| `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplatesTest.java` | `graph-query-core/` | 🟢 |  |

## access/mcp-server

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `access/mcp-server/pom.xml` | `mcp-server/` | 🟢 | `canonical-model`, `code-analysis.md`, `traceability` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/McpServerApplication.java` | `mcp-server/` | 🟢 | `inventory.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolArguments.java` | `mcp-server/` | 🟢 | `permissions.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java` | `mcp-server/` | 🟢 | `c4-components.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java` | `mcp-server/` | 🟢 | `adrs`, `integrations.md`, `permissions.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolDecision.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/package-info.java` | `mcp-server/` | 🟡 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphQueries.java` | `mcp-server/` | 🟢 | `c4-components.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java` | `mcp-server/` | 🟢 | `database`, `permissions.md`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderProperties.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/StalenessProperties.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/package-info.java` | `mcp-server/` | 🟡 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java` | `mcp-server/` | 🟢 | `integrations.md`, `openapi`, `permissions.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java` | `mcp-server/` | 🟢 | `architecture.md`, `c4-components.md`, `integrations.md`, `openapi` … |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java` | `mcp-server/` | 🟢 | `c4-components.md`, `integrations.md`, `openapi`, `tech-debt.md` … |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java` | `mcp-server/` | 🟢 | `domain.md`, `openapi`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java` | `mcp-server/` | 🟢 | `openapi`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/PingTool.java` | `mcp-server/` | 🟢 | `openapi`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java` | `mcp-server/` | 🟢 | `architecture.md`, `openapi`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java` | `mcp-server/` | 🟢 | `c4-components.md`, `domain.md`, `openapi`, `tech-debt.md` |
| `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/package-info.java` | `mcp-server/` | 🟡 |  |
| `access/mcp-server/src/main/resources/application.yml` | `mcp-server/` | 🟢 | `admin-ui`, `adrs`, `architecture.md`, `c4-containers.md` … |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/ActuatorProbesTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/FindRuntimeFootprintMcpTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/GetAssetMcpTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpAuditTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java` | `mcp-server/` | 🟢 | `integrations.md`, `tech-debt.md` |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/SearchAssetsMcpTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TestJwt.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TraceDependenciesMcpTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/audit/ToolArgumentsTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditorTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfigurationTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetToolTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsToolTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java` | `mcp-server/` | 🟢 |  |
| `access/mcp-server/src/test/resources/config/application.yml` | `mcp-server/` | 🟢 | `code-analysis.md` |

## adapters/adapter-core

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `adapters/adapter-core/pom.xml` | `adapter-core/` | 🟢 | `code-analysis.md`, `inventory.md`, `source-stubs` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/AdapterConfig.java` | `adapter-core/` | 🟢 | `deploymap-adapter`, `scm-adapter`, `source-spi`, `tech-debt.md` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java` | `adapter-core/` | 🟢 | `c4-components.md`, `deploymap-adapter`, `domain.md`, `eam-adapter` … |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/HttpSourceConnector.java` | `adapter-core/` | 🟢 | `asset-adapter`, `c4-components.md`, `deploymap-adapter`, `eam-adapter` … |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/InboxWriter.java` | `adapter-core/` | 🟢 | `c4-components.md`, `database`, `traceability` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Json.java` | `adapter-core/` | 🟢 | `demo-launcher`, `source-stubs` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/PollResult.java` | `adapter-core/` | 🟡 |  |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java` | `adapter-core/` | 🟢 | `adrs`, `c4-components.md`, `c4-containers.md`, `deploymap-adapter` … |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/RetryPolicy.java` | `adapter-core/` | 🟢 | `deploymap-adapter`, `integrations.md` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Sleeper.java` | `adapter-core/` | 🟡 |  |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/SourceAdapter.java` | `adapter-core/` | 🟢 | `demo-launcher`, `deploymap-adapter`, `tech-debt.md` |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java` | `adapter-core/` | 🟢 | `adrs`, `c4-components.md`, `integrations.md`, `permissions.md` … |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java` | `adapter-core/` | 🟢 | `adrs`, `c4-components.md`, `demo-launcher`, `ingestion-service` … |
| `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/package-info.java` | `adapter-core/` | 🟢 | `database` |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/HttpSourceConnectorTest.java` | `adapter-core/` | 🟢 | `source-stubs` |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/InMemoryJournal.java` | `adapter-core/` | 🟡 |  |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/InMemoryRawStore.java` | `adapter-core/` | n/a |  |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/JsonTest.java` | `adapter-core/` | 🟡 |  |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/PollerTest.java` | `adapter-core/` | 🟢 | `source-spi` |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/RetryPolicyTest.java` | `adapter-core/` | 🟡 |  |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/SourceAdapterTest.java` | `adapter-core/` | n/a |  |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/WebhookHandlerTest.java` | `adapter-core/` | 🟢 | `source-spi` |
| `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/WebhookServerControlTest.java` | `adapter-core/` | n/a |  |

## adapters/asset-adapter

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `adapters/asset-adapter/pom.xml` | `asset-adapter/` | 🟢 | `source-stubs` |
| `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter/AssetAdapter.java` | `asset-adapter/` | 🟢 | `c4-context.md`, `traceability` |
| `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter/AssetAdapterModule.java` | `asset-adapter/` | 🟡 |  |
| `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter/package-info.java` | `asset-adapter/` | 🟡 |  |
| `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter/AssetAdapterContractTest.java` | `asset-adapter/` | 🟢 |  |
| `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter/AssetAdapterSmokeTest.java` | `asset-adapter/` | 🟡 |  |

## adapters/deploymap-adapter

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `adapters/deploymap-adapter/pom.xml` | `deploymap-adapter/` | 🟢 | `canonical-model`, `code-analysis.md`, `source-stubs` |
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java` | `deploymap-adapter/` | 🟢 |  |
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterModule.java` | `deploymap-adapter/` | 🟢 |  |
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/package-info.java` | `deploymap-adapter/` | 🟢 |  |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java` | `deploymap-adapter/` | 🟢 | `source-stubs` |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterContractTest.java` | `deploymap-adapter/` | 🟢 |  |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterSmokeTest.java` | `deploymap-adapter/` | 🟢 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/README.md` | `deploymap-adapter/` | 🟢 | `code-analysis.md` |
| `adapters/deploymap-adapter/src/test/resources/deploymap/baseline/deployments.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/commit-change/deployments-v1.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/commit-change/deployments-v2.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/invalid-commit/deployments.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/legacy-poc-0/deployments.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/system-mismatch/deployments.json` | `deploymap-adapter/` | 🟡 |  |
| `adapters/deploymap-adapter/src/test/resources/deploymap/unknown-repository/deployments.json` | `deploymap-adapter/` | 🟡 |  |

## adapters/eam-adapter

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `adapters/eam-adapter/pom.xml` | `eam-adapter/` | 🟢 | `source-stubs` |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/CompositeEamConnector.java` | `eam-adapter/` | 🟢 | `integrations.md` |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/EamAdapter.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/EamAdapterModule.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/EamApiConnector.java` | `eam-adapter/` | 🟢 | `admin-ui`, `c4-components.md`, `integrations.md`, `tech-debt.md` |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/EamApiCursor.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/package-info.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter/CompositeEamConnectorTest.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter/EamAdapterContractTest.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter/EamAdapterSmokeTest.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter/EamApiConnectorTest.java` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter/EamApiFixturesContractTest.java` | `eam-adapter/` | 🟢 | `source-stubs` |
| `adapters/eam-adapter/src/test/resources/eam-api/README.md` | `eam-adapter/` | 🟢 | `code-analysis.md` |
| `adapters/eam-adapter/src/test/resources/eam-api/baseline/itproducts.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/baseline/itsystems.json` | `eam-adapter/` | 🟢 |  |
| `adapters/eam-adapter/src/test/resources/eam-api/baseline/platforms.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/deferred-reference/itproducts.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/deferred-reference/itsystems.json` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/resources/eam-api/options/itproducts-fields.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/options/itsystems-fields.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/options/itsystems-system.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/options/platforms-fields.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/pagination-empty-last/itproducts-page1.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/pagination-empty-last/itproducts-page2.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/pagination-empty-last/itproducts-page3.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/pagination-partial-last/platforms-page1.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/pagination-partial-last/platforms-page2.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/platform-mismatch/itproducts.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/platform-mismatch/itsystems.json` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/resources/eam-api/ref-change/itproducts-sync1.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/ref-change/itproducts-sync2.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/same-name/itsystems.json` | `eam-adapter/` | 🟡 |  |
| `adapters/eam-adapter/src/test/resources/eam-api/same-ver/itsystems-sync1.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/same-ver/itsystems-sync2.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/tombstone/platforms-snapshot1.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/tombstone/platforms-snapshot2.json` | `eam-adapter/` | n/a |  |
| `adapters/eam-adapter/src/test/resources/eam-api/types.json` | `eam-adapter/` | n/a |  |

## adapters/scm-adapter

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `adapters/scm-adapter/pom.xml` | `scm-adapter/` | 🟢 | `source-stubs` |
| `adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/ScmAdapter.java` | `scm-adapter/` | 🟢 | `c4-components.md` |
| `adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/ScmAdapterModule.java` | `scm-adapter/` | 🟡 |  |
| `adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/package-info.java` | `scm-adapter/` | 🟡 |  |
| `adapters/scm-adapter/src/test/java/io/github/unlocker/archrag/scmadapter/ScmAdapterContractTest.java` | `scm-adapter/` | 🟢 |  |
| `adapters/scm-adapter/src/test/java/io/github/unlocker/archrag/scmadapter/ScmAdapterSmokeTest.java` | `scm-adapter/` | 🟡 |  |

## contracts/canonical-model

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `contracts/canonical-model/pom.xml` | `canonical-model/` | 🟢 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/CanonicalModelModule.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/Invariants.java` | `canonical-model/` | 🟢 | `data-dictionary.md`, `domain.md` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/authority/AuthorityMatrix.java` | `canonical-model/` | 🟢 | `domain.md`, `state-machines.md` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/CloseAssertion.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/DeferRelation.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/GraphCommand.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/TombstoneSourceRecord.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/UpsertNode.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/command/UpsertRelation.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/CanonicalNode.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/ComputeInstance.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/ComputeKind.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Criticality.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Deployment.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Environment.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/EnvironmentClass.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/GitUrls.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/ITSystem.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/KubernetesCluster.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Lifecycle.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Namespace.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/NodeData.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/NodeLabel.java` | `canonical-model/` | 🟢 | `database` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Platform.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Repository.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Service.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Solution.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/Team.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/package-info.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/LookupKeys.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceKey.java` | `canonical-model/` | 🟢 | `ingestion-service` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceRecord.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceSystem.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceSystemCode.java` | `canonical-model/` | 🟢 | `ingestion-service` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SyncRun.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SyncRunStatus.java` | `canonical-model/` | 🟢 | `admin-ui`, `detective-gaps.md`, `state-machines.md` |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/relation/RelationType.java` | `canonical-model/` | 🟢 | `adrs`, `database`, `domain.md`, `erd-complete.md` … |
| `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/relation/Validity.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/CanonicalModelSmokeTest.java` | `canonical-model/` | n/a |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/authority/AuthorityMatrixTest.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/command/GraphCommandTest.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/node/GitUrlsTest.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/node/NodeTest.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/provenance/ProvenanceTest.java` | `canonical-model/` | 🟡 |  |
| `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/relation/RelationTypeTest.java` | `canonical-model/` | 🟡 |  |

## contracts/event-schemas

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `contracts/event-schemas/pom.xml` | `event-schemas/` | 🟢 | `canonical-model` |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/AssetEventData.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEvent.java` | `event-schemas/` | 🟢 | `adapter-core`, `code-analysis.md`, `event-journal` |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEventPublisher.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/Checkpoint.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/EventJournal.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/EventSchemasModule.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalEntry.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalKey.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalQuery.java` | `event-schemas/` | 🟢 | `ingestion-service` |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalReader.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/ObjectRef.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/ProcessingStatus.java` | `event-schemas/` | 🟢 | `detective-gaps.md`, `event-journal`, `ingestion-service`, `integration-tests` … |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/RawPayloadRef.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/RawPayloadStore.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SnapshotContents.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SourceVersion.java` | `event-schemas/` | 🟢 | `adrs`, `domain.md`, `source-spi`, `state-machines.md` |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/StoredEvent.java` | `event-schemas/` | 🟢 |  |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/package-info.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/AssetEventDataTest.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/CanonicalEventTest.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/EventSchemasSmokeTest.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/ProcessingStatusTest.java` | `event-schemas/` | 🟡 |  |
| `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/SourceVersionTest.java` | `event-schemas/` | 🟡 |  |

## contracts/source-spi

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `contracts/source-spi/pom.xml` | `source-spi/` | 🟢 | `canonical-model` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/ChangeOperation.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/ChangePage.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/Completeness.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceChange.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceConnector.java` | `source-spi/` | 🟢 | `eam-adapter` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java` | `source-spi/` | 🟢 | `adapter-core`, `asset-adapter`, `database`, `deploymap-adapter` … |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceUnavailableException.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookEvent.java` | `source-spi/` | 🟢 | `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java` | `source-spi/` | 🟢 | `adrs`, `integrations.md`, `permissions.md`, `source-stubs` |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/package-info.java` | `source-spi/` | 🟡 |  |
| `contracts/source-spi/src/test/java/io/github/unlocker/archrag/sourcespi/WebhookSignatureTest.java` | `source-spi/` | 🟢 |  |

## ingestion/event-journal

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `ingestion/event-journal/pom.xml` | `event-journal/` | 🟢 |  |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalException.java` | `event-journal/` | 🟢 |  |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java` | `event-journal/` | 🟢 | `code-analysis.md`, `database`, `integration-tests` |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java` | `event-journal/` | 🟢 | `architecture.md`, `c4-components.md`, `database`, `detective-gaps.md` … |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java` | `event-journal/` | 🟢 | `c4-components.md`, `database`, `ingestion-service`, `integrations.md` … |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/package-info.java` | `event-journal/` | 🟢 |  |
| `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql` | `event-journal/` | 🟢 | `admin-ui`, `database`, `erd-complete.md`, `state-machines.md` … |
| `ingestion/event-journal/src/test/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStoreTest.java` | `event-journal/` | n/a |  |

## ingestion/graph-projector

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `ingestion/graph-projector/pom.xml` | `graph-projector/` | 🟢 | `c4-components.md`, `canonical-model`, `code-analysis.md`, `normalizer` |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ConflictMarkers.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EamLookup.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java` | `graph-projector/` | 🟢 | `adapter-core`, `adrs`, `architecture.md`, `c4-components.md` … |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjection.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java` | `graph-projector/` | 🟢 | `admin-ui`, `adrs`, `c4-components.md`, `canonical-model` … |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjectorModule.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/NodeProperties.java` | `graph-projector/` | 🟢 | `domain.md` |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/NormalizationWarnings.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/PendingRelations.java` | `graph-projector/` | 🟢 | `c4-components.md`, `database` |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProcessingResult.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProjectionException.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProjectionOutcome.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProjectionRequest.java` | `graph-projector/` | 🟢 | `canonical-model` |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProjectionResult.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/PropertyJson.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/Reconciler.java` | `graph-projector/` | 🟢 | `adrs`, `architecture.md`, `c4-components.md`, `database` … |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ReconciliationTrigger.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/TemporalRelationMigration.java` | `graph-projector/` | 🟢 | `database`, `domain.md` |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/VersionDecision.java` | `graph-projector/` | 🟢 | `adrs`, `architecture.md`, `domain.md`, `event-schemas` … |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/package-info.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java` | `graph-projector/` | 🟢 | `canonical-model`, `database`, `graph-query-core`, `traceability` |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/EventProcessorTest.java` | `graph-projector/` | 🟢 |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/GraphProjectorSmokeTest.java` | `graph-projector/` | n/a |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/ProjectionRequestTest.java` | `graph-projector/` | 🟢 |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/PropertyJsonTest.java` | `graph-projector/` | 🟢 |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/ReconcilerTest.java` | `graph-projector/` | 🟢 |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/VersionDecisionTest.java` | `graph-projector/` | 🟡 |  |
| `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchemaTest.java` | `graph-projector/` | 🟢 | `database` |

## ingestion/identity-resolution

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `ingestion/identity-resolution/pom.xml` | `identity-resolution/` | 🟢 | `canonical-model` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/Crosswalk.java` | `identity-resolution/` | 🟢 | `domain.md`, `ingestion-service` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/CrosswalkConflictException.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/Feature.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureExtractor.java` | `identity-resolution/` | 🟢 | `domain.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureKind.java` | `identity-resolution/` | 🟢 | `adrs`, `domain.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureNormalizer.java` | `identity-resolution/` | 🟢 | `canonical-model`, `tech-debt.md`, `traceability` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityCandidate.java` | `identity-resolution/` | 🟢 | `detective-gaps.md`, `domain.md`, `state-machines.md`, `tech-debt.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityCandidates.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityMapping.java` | `identity-resolution/` | 🟢 | `domain.md`, `ingestion-service` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityResolutionModule.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityStoreException.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/LabelFamily.java` | `identity-resolution/` | 🟢 | `domain.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java` | `identity-resolution/` | 🟢 | `c4-components.md`, `database`, `state-machines.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java` | `identity-resolution/` | 🟢 | `c4-components.md`, `database`, `domain.md`, `erd-complete.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java` | `identity-resolution/` | 🟢 | `adrs`, `c4-components.md`, `database`, `domain.md` … |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/SourceConflict.java` | `identity-resolution/` | 🟢 | `domain.md`, `state-machines.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/SourceConflicts.java` | `identity-resolution/` | 🟡 |  |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/SourceKeys.java` | `identity-resolution/` | 🟢 | `domain.md` |
| `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/package-info.java` | `identity-resolution/` | 🟢 |  |
| `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql` | `identity-resolution/` | 🟢 | `database`, `erd-complete.md`, `traceability` |
| `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql` | `identity-resolution/` | 🟢 | `admin-ui`, `database` |
| `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql` | `identity-resolution/` | 🟢 | `database` |
| `ingestion/identity-resolution/src/main/resources/db/migration/V7__source_conflict_kind.sql` | `identity-resolution/` | 🟢 | `database` |
| `ingestion/identity-resolution/src/test/java/io/github/unlocker/archrag/identityresolution/CrosswalkTest.java` | `identity-resolution/` | n/a |  |
| `ingestion/identity-resolution/src/test/java/io/github/unlocker/archrag/identityresolution/FeatureExtractorTest.java` | `identity-resolution/` | n/a |  |
| `ingestion/identity-resolution/src/test/java/io/github/unlocker/archrag/identityresolution/FeatureNormalizerTest.java` | `identity-resolution/` | n/a |  |
| `ingestion/identity-resolution/src/test/java/io/github/unlocker/archrag/identityresolution/IdentityResolutionSmokeTest.java` | `identity-resolution/` | n/a |  |

## ingestion/ingestion-service

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `ingestion/ingestion-service/README.md` | `ingestion-service/` | 🟡 |  |
| `ingestion/ingestion-service/pom.xml` | `ingestion-service/` | 🟢 | `architecture.md`, `canonical-model`, `code-analysis.md`, `docker` … |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java` | `ingestion-service/` | 🟢 | `c4-components.md`, `integrations.md`, `tech-debt.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterUnavailableException.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdaptersProperties.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java` | `ingestion-service/` | 🟢 | `database`, `integration-tests` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminBusyException.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java` | `ingestion-service/` | 🟢 | `c4-components.md`, `c4-context.md`, `detective-gaps.md`, `domain.md` … |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java` | `ingestion-service/` | 🟢 | `architecture.md`, `c4-components.md`, `c4-containers.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java` | `ingestion-service/` | 🟢 | `admin-console`, `adrs`, `tech-debt.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminProperties.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java` | `ingestion-service/` | 🟢 | `admin-console`, `permissions.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/BadRequestException.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkResult.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java` | `ingestion-service/` | 🟢 | `domain.md`, `erd-complete.md`, `identity-resolution` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherProperties.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java` | `ingestion-service/` | 🟢 | `c4-components.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java` | `ingestion-service/` | 🟢 | `database`, `detective-gaps.md`, `event-journal`, `state-machines.md` … |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceApplication.java` | `ingestion-service/` | 🟢 | `inventory.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java` | `ingestion-service/` | 🟢 | `architecture.md`, `c4-containers.md`, `code-analysis.md`, `database` … |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java` | `ingestion-service/` | 🟢 | `adrs`, `c4-components.md`, `database`, `state-machines.md` … |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/Neo4jProperties.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java` | `ingestion-service/` | 🟢 | `c4-components.md`, `database`, `erd-complete.md`, `tech-debt.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayResult.java` | `ingestion-service/` | 🟢 |  |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java` | `ingestion-service/` | 🟢 | `state-machines.md`, `tech-debt.md`, `traceability` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/S3Properties.java` | `ingestion-service/` | 🟢 | `database` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java` | `ingestion-service/` | 🟢 | `c4-components.md`, `integrations.md`, `permissions.md` |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java` | `ingestion-service/` | 🟢 | `adapter-core`, `c4-components.md`, `state-machines.md`, `tech-debt.md` … |
| `ingestion/ingestion-service/src/main/resources/application.yml` | `ingestion-service/` | 🟢 | `event-journal`, `integrations.md`, `permissions.md` |
| `ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql` | `ingestion-service/` | 🟢 | `data-dictionary.md`, `database`, `erd-complete.md`, `integration-tests` … |
| `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql` | `ingestion-service/` | 🟢 | `admin-console`, `admin-ui`, `c4-containers.md`, `database` … |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/adaptercore/StoredEventReaderContractTest.java` | `ingestion-service/` | 🟢 | `adapter-core` |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClientTest.java` | `ingestion-service/` | n/a |  |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/AdminControllerTest.java` | `ingestion-service/` | n/a |  |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/AdminOperationsTest.java` | `ingestion-service/` | n/a |  |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidatorTest.java` | `ingestion-service/` | n/a |  |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/IngestionMetricsTest.java` | `ingestion-service/` | n/a |  |
| `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcherTest.java` | `ingestion-service/` | n/a |  |

## ingestion/normalizer

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `ingestion/normalizer/pom.xml` | `normalizer/` | 🟢 | `canonical-model`, `code-analysis.md`, `inventory.md`, `source-stubs` … |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/CanonicalMapper.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/CmdbMapper.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/CommandSink.java` | `normalizer/` | 🟢 | `adrs`, `domain.md`, `state-machines.md` |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/DeployMapMapper.java` | `normalizer/` | 🟢 | `source-stubs`, `traceability` |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/DeploymentLookup.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/EamMapper.java` | `normalizer/` | 🟢 | `adrs` |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/NormalizationException.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/NormalizationResult.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/Normalizer.java` | `normalizer/` | 🟢 | `c4-components.md`, `canonical-model`, `state-machines.md`, `tech-debt.md` … |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/NormalizerModule.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/Payload.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/ReferenceResolver.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/ScmMapper.java` | `normalizer/` | 🟢 | `canonical-model`, `domain.md`, `identity-resolution`, `tech-debt.md` … |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/UnresolvedReference.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/package-info.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/DeployMapMapperTest.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/EamArchObjectMapperTest.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/Fixtures.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/NormalizerSmokeTest.java` | `normalizer/` | n/a |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/NormalizerTest.java` | `normalizer/` | 🟡 |  |
| `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/ScmMapperTest.java` | `normalizer/` | n/a |  |

## platform/demo-launcher

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `platform/demo-launcher/pom.xml` | `demo-launcher/` | 🟢 | `code-analysis.md`, `deploymap-adapter`, `source-stubs` |
| `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java` | `demo-launcher/` | 🟢 | `deploymap-adapter`, `docker`, `event-journal`, `inventory.md` |
| `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java` | `demo-launcher/` | 🟢 |  |
| `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java` | `demo-launcher/` | 🟢 | `asset-adapter`, `docker`, `eam-adapter` |
| `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java` | `demo-launcher/` | 🟢 |  |
| `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/package-info.java` | `demo-launcher/` | 🟢 |  |
| `platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java` | `demo-launcher/` | 🟢 | `eam-adapter` |
| `platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java` | `demo-launcher/` | 🟢 |  |
| `platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java` | `demo-launcher/` | 🟢 |  |

## platform/docker

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `platform/docker/.env.example` | `docker/` | 🟢 | `database`, `inventory.md` |
| `platform/docker/Dockerfile` | `docker/` | 🟢 | `code-analysis.md`, `demo-launcher` |
| `platform/docker/Dockerfile.dockerignore` | `docker/` | 🟢 |  |
| `platform/docker/README.md` | `docker/` | 🟢 | `admin-ui`, `code-analysis.md`, `data-dictionary.md`, `demo-launcher` … |
| `platform/docker/certs/gen-certs.sh` | `docker/` | 🟢 |  |
| `platform/docker/console-smoke.sh` | `docker/` | 🟢 | `admin-console`, `admin-ui`, `code-analysis.md`, `database` … |
| `platform/docker/docker-compose.yml` | `docker/` | 🟢 | `admin-console`, `admin-ui`, `architecture.md`, `c4-containers.md` … |
| `platform/docker/entrypoint.sh` | `docker/` | 🟢 | `code-analysis.md`, `data-dictionary.md`, `demo-launcher` |
| `platform/docker/keycloak/archrag-realm.json` | `docker/` | 🟢 | `admin-console`, `admin-ui`, `c4-context.md`, `integrations.md` … |
| `platform/docker/neo4j/init-reader.sh` | `docker/` | 🟢 | `code-analysis.md`, `database`, `mcp-server`, `permissions.md` |
| `platform/docker/observability/grafana/dashboards/archrag-poc.json` | `docker/` | 🟢 |  |
| `platform/docker/observability/grafana/provisioning/dashboards/archrag.yml` | `docker/` | 🟢 |  |
| `platform/docker/observability/grafana/provisioning/datasources/prometheus.yml` | `docker/` | 🟢 |  |
| `platform/docker/observability/otel-collector.yml` | `docker/` | 🟢 |  |
| `platform/docker/observability/prometheus.yml` | `docker/` | 🟢 |  |
| `platform/docker/postgres/init-console-ro.sh` | `docker/` | 🟢 | `database` |
| `platform/docker/proxy/nginx.conf` | `docker/` | 🟢 | `c4-containers.md`, `c4-context.md`, `demo-launcher`, `deploymap-adapter` … |
| `platform/docker/scripts/backup-restore-check.sh` | `docker/` | 🟢 | `code-analysis.md`, `demo-launcher` |
| `platform/docker/scripts/backup.sh` | `docker/` | 🟢 |  |
| `platform/docker/scripts/deployment-commit-check.sh` | `docker/` | 🟢 | `demo-launcher` |
| `platform/docker/scripts/graph-fingerprint.sh` | `docker/` | 🟢 |  |
| `platform/docker/scripts/lib.sh` | `docker/` | 🟢 |  |
| `platform/docker/scripts/measure.sh` | `docker/` | 🟢 | `demo-launcher` |
| `platform/docker/scripts/restore.sh` | `docker/` | 🟢 |  |
| `platform/docker/smoke.sh` | `docker/` | 🟢 | `code-analysis.md`, `demo-launcher` |

## platform/integration-tests

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `platform/integration-tests/pom.xml` | `integration-tests/` | 🟢 | `canonical-model`, `code-analysis.md`, `data-dictionary.md`, `normalizer` … |
| `platform/integration-tests/src/main/java/io/github/unlocker/archrag/integrationtests/IntegrationTestsModule.java` | `integration-tests/` | 🟢 | `code-analysis.md` |
| `platform/integration-tests/src/main/java/io/github/unlocker/archrag/integrationtests/package-info.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java` | `integration-tests/` | 🟢 | `admin-console` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java` | `integration-tests/` | 🟢 | `ingestion-service` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AssetFixtures.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/CanonicalGraph.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ContainersSmokeIT.java` | `integration-tests/` | 🟢 | `docker` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiProjectionIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiSupport.java` | `integration-tests/` | 🟢 | `source-stubs` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java` | `integration-tests/` | 🟢 | `database` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java` | `integration-tests/` | 🟢 | `database`, `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java` | `integration-tests/` | 🟢 | `mcp-server`, `tech-debt.md` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java` | `integration-tests/` | 🟢 | `graph-projector` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java` | `integration-tests/` | 🟢 | `architecture.md`, `graph-query-core`, `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityCandidateIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java` | `integration-tests/` | 🟢 | `database` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IntegrationTestsSmokeTest.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java` | `integration-tests/` | 🟢 | `ingestion-service` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/McpTestJwt.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java` | `integration-tests/` | 🟢 | `database` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PlatformInvariantIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java` | `integration-tests/` | 🟢 | `graph-projector`, `source-stubs` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java` | `integration-tests/` | 🟢 | `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ScmItSystemMapper.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java` | `integration-tests/` | 🟢 | `graph-query-core`, `mcp-server` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java` | `integration-tests/` | 🟢 |  |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java` | `integration-tests/` | 🟢 | `source-stubs` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java` | `integration-tests/` | 🟢 | `admin-console`, `database` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TestImages.java` | `integration-tests/` | 🟢 | `docker`, `tech-debt.md` |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java` | `integration-tests/` | 🟢 | `mcp-server` |

## platform/source-stubs

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `platform/source-stubs/pom.xml` | `source-stubs/` | 🟢 | `code-analysis.md` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java` | `source-stubs/` | 🟢 | `deploymap-adapter`, `source-spi` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java` | `source-stubs/` | 🟢 | `demo-launcher`, `eam-adapter` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java` | `source-stubs/` | 🟢 | `demo-launcher` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java` | `source-stubs/` | 🟢 | `demo-launcher` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java` | `source-stubs/` | 🟢 | `asset-adapter` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java` | `source-stubs/` | 🟢 | `demo-launcher`, `source-spi` |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/package-info.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/EamApiStubTest.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/JsonTest.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceServerTest.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java` | `source-stubs/` | 🟢 |  |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java` | `source-stubs/` | 🟢 | `source-spi` |

## repo-root

| Файл | Unit | Покрытие | Ещё цитируют |
|------|------|----------|--------------|
| `.github/workflows/ci.yml` | `—/` | 🟢 | `admin-ui`, `code-analysis.md`, `docker`, `integration-tests` … |
| `.gitignore` | `—/` | 🟢 | `admin-ui`, `code-analysis.md`, `data-dictionary.md`, `docker` … |
| `.mvn/wrapper/maven-wrapper.properties` | `—/` | 🟢 | `inventory.md` |
| `CLAUDE.md` | `—/` | 🟢 | `adapter-core`, `admin-console`, `admin-ui`, `canonical-model` … |
| `README.md` | `—/` | 🟢 | `admin-ui`, `code-analysis.md`, `data-dictionary.md`, `demo-launcher` … |
| `access/pom.xml` | `—/` | 🟢 | `admin-ui`, `inventory.md` |
| `adapters/pom.xml` | `—/` | 🟢 | `asset-adapter`, `deploymap-adapter`, `inventory.md`, `scm-adapter` |
| `contracts/pom.xml` | `—/` | 🟢 | `event-schemas`, `inventory.md` |
| `ingestion/pom.xml` | `—/` | 🟢 | `inventory.md` |
| `mvnw` | `—/` | 🟢 | `admin-ui`, `asset-adapter`, `canonical-model`, `code-analysis.md` … |
| `mvnw.cmd` | `—/` | n/a |  |
| `platform/pom.xml` | `—/` | 🟢 | `inventory.md` |
| `pom.xml` | `—/` | 🟢 | `adapter-core`, `admin-console`, `admin-ui`, `architecture.md` … |
