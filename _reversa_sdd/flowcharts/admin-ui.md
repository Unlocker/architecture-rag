# Блок-схемы: access/admin-ui

> Archaeologist, R.2d. Пути относительно `access/admin-ui/src/`. Маршруты и API — `code-analysis.md` §X4.1; REST-сторона — `flowcharts/admin-console.md`.

## Старт и вход (`index.tsx:8-10`, `App.tsx:16-33`, `auth/authService.ts:53-75`) 🟢

```mermaid
flowchart TD
  S["index.tsx: loadConsoleConfig()"] --> C{"GET /console-config.json ok\nи заданы issuer, clientId, scope?"}
  C -- нет --> E1["«Не удалось загрузить конфигурацию консоли»"]
  C -- да --> I["App: auth.init()"]
  I --> Q{"в адресе ?code и ?state?"}
  Q -- да --> CB["signinRedirectCallback()"]
  CB -- ошибка --> CL1["убрать code/state из адреса → false"]
  CB -- ok --> CL2["убрать code/state; state '#/…' → восстановить hash → user"]
  Q -- нет --> GU["manager.getUser() из InMemoryWebStorage"]
  CL1 --> R
  CL2 --> R
  GU --> R{"user есть и не просрочен?"}
  R -- да --> RD["состояние ready → Layout + ApiProvider(key=route)"]
  R -- нет --> LG["auth.login(): редирект на IdP (Code + PKCE, state = hash)"]
  I -- "исключение" --> F["«Не удалось выполнить вход»"]
```

## Запрос к API и реакция на 401/403 (`api/apiClient.ts:43-69`, `api/ApiContext.tsx:12-37`) 🟢

```mermaid
flowchart TD
  A["api.get(path)"] --> T["токен из памяти (или нет)"]
  T --> F["fetch GET path, Authorization: Bearer"]
  F --> ST{"статус"}
  ST -- 401 --> U["onUnauthorized: login() один раз на параллельные 401;\nUnauthorizedError"]
  ST -- 403 --> FB["onForbidden: экран «Нет прав» в разделе;\nForbiddenError"]
  ST -- "прочий не-2xx" --> AE["ApiError(status) → «Ошибка запроса (HTTP N)»"]
  ST -- "2xx" --> J["response.json()"]
  U -- "login() упал" --> LF["«Не удалось выполнить вход»"]
```

## Раздел «Граф» (`pages/GraphPage.tsx`, `pages/graph/*`) 🟢

```mermaid
flowchart TD
  G["GraphPage"] --> SUM["GraphSummary: GET /api/graph/stats (+ кнопка «Обновить»)"]
  G --> SRCH["AssetSearch: submit (q не пуст) → GET /api/graph/search\n(устаревший ответ отбрасывается счётчиком latest)"]
  SRCH --> SEL["клик по имени → selected = gid"]
  SEL --> CARD["AssetCard: GET /api/graph/nodes/{gid}\nсвойства, жизненный цикл, происхождение, блок «Исходный код»"]
  SEL --> NB["NeighborhoodView: GET …/neighborhood?depth&relTypes"]
  NB --> TR{"truncated?"}
  TR -- да --> BN["баннер «показана не полностью»"]
  TR -- нет --> CV
  BN --> CV["GraphCanvas (Cytoscape, раскладка cose)"]
  CV --> CK["клик по узлу → selected = gid узла"]
  CK --> SEL
```

## Раздел «Синхронизация» (`pages/SyncPage.tsx`, `pages/sync/*`) 🟢

```mermaid
flowchart TD
  SP["SyncPage: вкладка по умолчанию «Источники»"] --> T1["Источники: GET /api/sync/sources каждые 30 с;\nhealth(): bad / warn / ok"]
  SP --> T2["Журнал: фильтры source, status, from, to → GET /api/sync/events;\nклик по eventId → GET /api/sync/events/{source}/{eventId}"]
  SP --> T3["DLQ: GET /api/sync/dlq, по умолчанию replayed=false"]
  SP --> T4["Identity: GET /api/identity/conflicts и /candidates (status OPEN|RESOLVED)"]
  SP --> T5["Аудит: GET /api/audit (page)"]
  T1 --> H{"health(s)"}
  H -- "события QUARANTINED/RETRYING, FAILED/failed>0, lag>3600" --> BAD["bad «проблемы»"]
  H -- "нет lastProjectedAt / lastSyncRun, lag>900" --> WARN["warn «нет данных»"]
  H -- иначе --> OK["ok «норма»"]
```
