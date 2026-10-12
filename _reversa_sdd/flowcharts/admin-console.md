# Блок-схемы: access/admin-console

> Archaeologist, R.2d. Пути относительно `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/`. Шаблоны графа — `flowcharts/graph-query-core.md`. REST-таблица и ответы — `code-analysis.md` §X3.1, `data-dictionary.md` §X.3.

## Авторизация и маршрутизация запроса (`security/ConsoleSecurityConfiguration.java:38-53`, `config/SpaWebConfiguration.java:48-71`) 🟢

```mermaid
flowchart TD
  R["HTTP-запрос"] --> M{"путь / метод"}
  M -- "GET /actuator/health/**, GET /console-config.json" --> PUB["публично"]
  M -- "/api/** (любой метод)" --> J{"JWT: issuer + audience консоли?"}
  J -- нет --> U401["401"]
  J -- да --> SC{"scope architecture.admin?"}
  SC -- нет --> F403["403"]
  SC -- да --> CT["ReadAuditInterceptor.preHandle → контроллер"]
  M -- "прочий /actuator/**" --> D["denyAll"]
  M -- "GET /** (остальное)" --> SP["статика из META-INF/resources;\nпуть без точки (не api/actuator) → index.html;\nотсутствующий файл с расширением → 404"]
  M -- "не GET вне /api" --> D
  CT --> OK["ответ JSON"]
  OK --> AU["afterCompletion: одна запись archrag.audit.console\n(sub, endpoint-шаблон, templates, rows, truncated, status, durationMs)"]
```

## `GET /api/graph/nodes/{gid}/neighborhood` (`api/GraphController.java:127-167`) 🟢

```mermaid
flowchart TD
  A["neighborhood(gid, depth?, relTypes?)"] --> G["gid → UUID, иначе 400"]
  G --> D{"1 ≤ depth ≤ 2 (по умолч. 1)?"}
  D -- нет --> E400["400 invalid_request"]
  D -- да --> T{"relTypes ⊆ NEIGHBORHOOD_RELATION_TYPES?\n(пусто → все)"}
  T -- нет --> E400
  T -- да --> N["console_neighborhood_nodes(gid, relTypes), budget.maxDepth = depth"]
  N --> EM{"узлов нет?"}
  EM -- да --> E404["404 not_found"]
  EM -- нет --> ED["console_neighborhood_edges(gids = gid узлов, relTypes)"]
  ED --> RES["Neighborhood(center, nodes, edges, truncated = nodes.truncated ИЛИ edges.truncated)"]
```

## `GET /api/sync/sources` (`api/SyncController.java:56-75`) 🟢

```mermaid
flowchart TD
  S["sources()"] --> C1["PG: consumer_checkpoint"]
  S --> C2["PG: count(*) по (source, status)"]
  S --> C3["PG: max(updated_at) для PROJECTED по source"]
  S --> C4["Neo4j: console_latest_sync_runs(adapters = CODES в верхнем регистре)"]
  C1 & C2 & C3 & C4 --> L["для каждого из 4 кодов (eam, scm, cmdb, deploymap)"]
  L --> B["eventsByStatus: все 10 статусов, отсутствующие = 0"]
  L --> LG{"есть PROJECTED?"}
  LG -- да --> LAG["lagSeconds = max(0, now − last)"]
  LG -- нет --> NUL["lastProjectedAt = null, lagSeconds = null"]
```

## Страница из PostgreSQL (`pg/ConsoleReadRepository.java:271-290`) 🟢

```mermaid
flowchart TD
  P["page(table, columns, where, orderBy, PageRequest)"] --> W["Where: константные фрагменты + именованные параметры;\nnull-фильтры пропускаются"]
  W --> CN["SELECT count(*) FROM table WHERE …"]
  CN --> Q["SELECT columns FROM table WHERE … ORDER BY orderBy LIMIT :limit OFFSET :offset"]
  Q --> A["ReadAuditContext.recordSql('sql:table', rows)"]
  A --> R["Page(items, page, size, total)"]
```
