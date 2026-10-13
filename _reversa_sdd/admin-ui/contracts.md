# access/admin-ui: внешние контракты

> Что UI вызывает у бэкенда, что читает из конфигурации и IdP, что публикует сборка. Секретов и значений токенов нет; приводятся только имена. Серверные обработчики — `access/admin-console` (отдельной SDD-папки нет, см. [`../flowcharts/admin-console.md`](../flowcharts/admin-console.md)).

## 1. HTTP-вызовы к бэкенду (все `GET`)

Общее (🟢): единственная функция отправки — `request(method, path, body?)`; `get` вызывает её с `'GET'` (`access/admin-ui/src/api/apiClient.ts:43, 72`). Заголовки: `Accept: application/json`; `Authorization: Bearer <access token>` — если токен есть; `Content-Type` — только при теле (`access/admin-ui/src/api/apiClient.ts:44-51`). Путь относительный (тот же origin, что и страница); CORS не используется 🟢. Код возврата: 401 → повторный вход, 403 → экран «Нет прав», прочее не-2xx → `ApiError`, 204 → `undefined`, иначе `response.json()` (`access/admin-ui/src/api/apiClient.ts:57-68`). Серверная авторизация: `/api/**` требует authority `SCOPE_architecture.admin` при JWT с issuer Keycloak и audience консоли (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:44-45`; `access/admin-console/src/main/resources/application.yml:14-15`) 🟢.

| № | Метод и путь | Query | Ответ (тип UI) | Вызов в UI | Обработчик бэкенда |
|---|--------------|-------|----------------|------------|--------------------|
| 1 | `GET /console-config.json` | — | `ConsoleConfig{issuer, clientId, scope}` | `access/admin-ui/src/auth/authService.ts:23` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:27-29` (публично) |
| 2 | `GET /api/graph/stats` | — | `Stats` | `access/admin-ui/src/api/endpoints.ts:49`, `access/admin-ui/src/pages/graph/GraphSummary.tsx:9` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:56` |
| 3 | `GET /api/graph/search` | `q` (обязателен, ≤ 200 в поле ввода), `types` (повторяющийся ключ) | `SearchResponse{items[], truncated}` | `access/admin-ui/src/api/endpoints.ts:50-51`, `access/admin-ui/src/pages/graph/AssetSearch.tsx:23` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:68-103` (`limit` UI не шлёт; по умолчанию 10, максимум 50: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:40-41`) |
| 4 | `GET /api/graph/nodes/{gid}` | — | `NodeCard` | `access/admin-ui/src/api/endpoints.ts:52`, `access/admin-ui/src/pages/graph/AssetCard.tsx:33` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:105-106` |
| 5 | `GET /api/graph/nodes/{gid}/neighborhood` | `depth` (1 или 2), `relTypes` (повторяющийся ключ) | `Neighborhood{center, nodes[], edges[], truncated}` | `access/admin-ui/src/api/endpoints.ts:53-56`, `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:14` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:127-131` |
| 6 | `GET /api/sync/sources` | — | `SourceStatus[]` (всегда 4 источника) | `access/admin-ui/src/api/endpoints.ts:57`, `access/admin-ui/src/pages/sync/SourcesTab.tsx:30` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:56` |
| 7 | `GET /api/sync/events` | `source`, `status`, `from`, `to` (ISO UTC), `page`, `size` | `Page<SyncEvent>` | `access/admin-ui/src/api/endpoints.ts:58`, `access/admin-ui/src/pages/sync/EventsTab.tsx:59-62` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:78-85` |
| 8 | `GET /api/sync/events/{source}/{eventId}` | — | `EventDetail{event, errorReason, dlqEntries[], historyNote}` | `access/admin-ui/src/api/endpoints.ts:59-62`, `access/admin-ui/src/pages/sync/EventsTab.tsx:12` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:97-98` |
| 9 | `GET /api/sync/dlq` | `source`, `replayed` (`false` по умолчанию в UI), `page`, `size` | `Page<DlqEntry>` | `access/admin-ui/src/api/endpoints.ts:63-64`, `access/admin-ui/src/pages/sync/DlqTab.tsx:16-19` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:113-118` |
| 10 | `GET /api/identity/conflicts` | `status` (`OPEN`/`RESOLVED`), `page`, `size` | `Page<Conflict>` | `access/admin-ui/src/api/endpoints.ts:65-66`, `access/admin-ui/src/pages/sync/IdentityTab.tsx:15` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:30-35` (UI не шлёт `gid`) |
| 11 | `GET /api/identity/candidates` | `status`, `page`, `size` | `Page<Candidate>` | `access/admin-ui/src/api/endpoints.ts:67-68`, `access/admin-ui/src/pages/sync/IdentityTab.tsx:67` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:41-46` (UI не шлёт `labelFamily`) |
| 12 | `GET /api/audit` | `page`, `size` | `Page<Audit>` | `access/admin-ui/src/api/endpoints.ts:69-70`, `access/admin-ui/src/pages/sync/AuditTab.tsx:13` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:25-32` (UI не шлёт `operation`, `status`, `from`, `to`, хотя тип `audit` их допускает: `access/admin-ui/src/api/endpoints.ts:69`) |


Кодирование (🟢): сегменты пути — `encodeURIComponent` (`gid`, `source`, `eventId`); query — `URLSearchParams`, пустые значения (`undefined`, `null`, `''`) пропускаются, массивы повторяют ключ (`access/admin-ui/src/api/endpoints.ts:20-32, 52, 55, 61`). Булево `replayed=false` отправляется (значение `false` не пустое) 🟢 (`access/admin-ui/src/api/endpoints.ts:23`).

Ограничения сервера, которые UI учитывает (🟢): `page ≥ 0`, `1 ≤ size ≤ 200` (UI шлёт 50) (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java:14-24`); глубина окрестности ≤ 2 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:44`); неизвестный `source`/`status`/тип — `400` без эха значения (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:36-62`).

## 2. `GET /console-config.json`

Публичный ответ JSON `{issuer, clientId, scope}`; значения по умолчанию `clientId = archrag-admin-console`, `scope = "openid architecture.admin"`; `issuer` = `spring.security.oauth2.resourceserver.jwt.issuer-uri` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:18, 28-29`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java:16-17`; `access/admin-console/src/main/resources/application.yml:21-22`) 🟢. UI требует все три поля непустыми (`access/admin-ui/src/auth/authService.ts:28-30`) 🟢.

## 3. OIDC (клиент `oidc-client-ts` 3.1.0)

| Параметр | Значение | Трассировка |
|----------|----------|-------------|
| `authority` | `issuer` из конфигурации | `access/admin-ui/src/auth/authService.ts:42` 🟢 |
| `client_id` | `clientId` из конфигурации (на стенде `archrag-admin-console`, публичный клиент, `standardFlowEnabled`, `directAccessGrantsEnabled=false`) | `access/admin-ui/src/auth/authService.ts:43`; `platform/docker/keycloak/archrag-realm.json:77-90` 🟢 |
| `response_type` | `code` (PKCE подставляет библиотека; метод S256 задан в realm: `pkce.code.challenge.method`) | `access/admin-ui/src/auth/authService.ts:46`; `platform/docker/keycloak/archrag-realm.json:86` 🟢 / 🟡 (метод вычисляет библиотека) |
| `redirect_uri`, `post_logout_redirect_uri` | `${window.location.origin}/` | `access/admin-ui/src/auth/authService.ts:40, 44-45` 🟢 |
| `scope` | из конфигурации (`openid architecture.admin`) | `access/admin-ui/src/auth/authService.ts:47` 🟢 |
| `userStore` | `WebStorageStateStore` на `InMemoryWebStorage` | `access/admin-ui/src/auth/authService.ts:48` 🟢 |
| `state` при входе | `window.location.hash` (восстанавливается, если начинается с `#/`) | `access/admin-ui/src/auth/authService.ts:68-70, 77` 🟢 |
| Redirect URI в realm | только `http://127.0.0.1:<ARCHRAG_CONSOLE_PORT>/*` | `platform/docker/keycloak/archrag-realm.json:87-88` 🟢 |
| Access token | `aud` = `urn:archrag:console` (mapper клиента), `scope` включает `architecture.admin` (optional-scope клиента) | `platform/docker/keycloak/archrag-realm.json:90, 91-101` 🟢 |

## 4. Маршруты и состояние адресной строки

`#/graph`, `#/sync` (`access/admin-ui/src/useHashRoute.ts:5-8`) 🟢. Параметры `?code=…&state=…` обрабатываются только в `init()` и сразу убираются из адреса `history.replaceState` (`access/admin-ui/src/auth/authService.ts:54-58`) 🟢. Бэкенд отдаёт `index.html` для путей без расширения (SPA fallback), для `api/**` и `actuator/**` fallback отключён (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:62-70`) 🟢.

## 5. Артефакт сборки

| Что | Значение | Трассировка |
|-----|----------|-------------|
| Артефакт Maven | `admin-ui`, packaging `jar`, родитель `access` | `access/admin-ui/pom.xml:7-16` 🟢 (в `pom.xml` нет `<packaging>`; значение по умолчанию Maven — `jar`) |
| Выход Webpack | `target/classes/META-INF/resources`: `index.html` (из `src/index.html`), `[name].[contenthash].js`, `[name].[contenthash].css`, `publicPath: '/'`, `clean: true` | `access/admin-ui/webpack.config.js:5-18, 27-30` 🟢 |
| Точка входа | `./src/index.tsx` | `access/admin-ui/webpack.config.js:12` 🟢 |
| Корневой контейнер | `<div id="root">` (Java-тест CSP разбирает `id="root"`) | `access/admin-ui/src/index.html:9`; `access/admin-ui/webpack.config.js:31-33` 🟢 |
| Dev-сервер | порт 3000, `historyApiFallback`, прокси `/console-config.json` и `/api` на `ADMIN_CONSOLE_URL` (по умолчанию `http://localhost:8082`) | `access/admin-ui/webpack.config.js:9, 36-46` 🟢 |
| Скрипты npm | `build` = `webpack --mode production`; `start` = `webpack serve --mode development`; `test` = `jest` | `access/admin-ui/package.json:6-10` 🟢 |
| Jest | среда `jsdom`, корень `src`, `*.test.ts(x)`, `ts-jest` | `access/admin-ui/jest.config.js:1-6` 🟢 |

## 6. Формы ответов (`access/admin-ui/src/api/types.ts`)

| Тип | Поля (кратко) | Строки |
|-----|---------------|--------|
| `Page<T>` | `items, page, size, total` | `access/admin-ui/src/api/types.ts:76-81` |
| `Stats` | `nodes[{name,count}], relations[{name,count}], lastUpdatedAt\|null` | `access/admin-ui/src/api/types.ts:3-12` |
| `SearchResponse` / `AssetHit` | `items[{gid,type,name,score,matchType,sources[],lastSeenAt\|null}], truncated` | `access/admin-ui/src/api/types.ts:14-27` |
| `NodeCard` / `SourceRecordInfo` | `gid,type,properties{},firstSeenAt,lastSeenAt,deletedAt,isCurrent,sources[{source,sourceType,sourceId,sourceVersion,fetchedAt,contentHash,active,deletedAt,authority,confidence,conflicts[]}]` (значений свойств актива в записи источника нет) | `access/admin-ui/src/api/types.ts:29-53` |
| `Neighborhood` | `center, nodes[{gid,type,name,isCurrent,distance}], edges[{type,source,target}], truncated` | `access/admin-ui/src/api/types.ts:55-74` |
| `SourceStatus` | `source, checkpoints[{consumer,cursor,updatedAt}], lastSyncRun{runId,startedAt,endedAt,status,fetched,applied,failed}\|null, eventsByStatus{}, lastProjectedAt, lagSeconds` | `access/admin-ui/src/api/types.ts:83-106` |
| `SyncEvent` | метаданные inbox без payload: `source,eventId,type,subject,sourceType,sourceId,sourceVersion,correlationId,syncRunId,schemaVersion,status,attempts,errorCode,payloadRef,contentHash,receivedAt,updatedAt` | `access/admin-ui/src/api/types.ts:108-127` |
| `DlqEntry`, `EventDetail` | `id,source,eventId,errorCode,reason,payloadRef,createdAt,replayedAt`; `event,errorReason,dlqEntries[],historyNote` | `access/admin-ui/src/api/types.ts:129-145` |
| `Conflict` | `gid,property,dissent*,master*,status,openedAt,updatedAt,resolvedAt,kind?('PROPERTY'\|'RELATION')` | `access/admin-ui/src/api/types.ts:147-164` |
| `Candidate` | `left*/right*(source,type,id), labelFamily, matched[{feature,value}]\|null, score, status, firstSeenAt, updatedAt` | `access/admin-ui/src/api/types.ts:166-184` |
| `Audit` | `id,operation,actor,status,replayId,request,requestTruncated,result,resultTruncated,error,startedAt,finishedAt` | `access/admin-ui/src/api/types.ts:186-199` |

Типы — копия формы Java-records (`access/admin-ui/src/api/types.ts:1`); общего генератора или контрактного теста нет 🟢 [R.8: ответ владельца, вопрос 9] (Q-05).

## 7. Константы UI (`access/admin-ui/src/api/constants.ts`)

| Константа | Значение | Строки |
|-----------|----------|--------|
| `ASSET_TYPES` | `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance` | `access/admin-ui/src/api/constants.ts:5-13` |
| `RELATION_TYPES` | `DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON, DEPENDS_ON, OWNED_BY, HOSTED_ON, DEPLOYED_FROM` | `access/admin-ui/src/api/constants.ts:15-25` |
| `SOURCES` | `eam, scm, cmdb, deploymap` | `access/admin-ui/src/api/constants.ts:27` |
| `EVENT_STATUSES` | 10 значений `ProcessingStatus` | `access/admin-ui/src/api/constants.ts:29-40` |
| `STALE_AFTER_MS` | 7 суток | `access/admin-ui/src/api/constants.ts:56` |
| `SOURCES_REFRESH_MS` | 30 000 | `access/admin-ui/src/api/constants.ts:59` |
| `LAG_WARN_SECONDS` / `LAG_BAD_SECONDS` | 900 / 3600 | `access/admin-ui/src/api/constants.ts:62-63` |
| `LABEL_COLORS`, `DEFAULT_LABEL_COLOR` | палитра узлов по метке; цвет по умолчанию для неизвестной метки | `access/admin-ui/src/api/constants.ts:43-53` |

Сверка с бэкендом на момент снимка (🟢): `ASSET_TYPES` = `AssetTemplates.SEARCHABLE_TYPES` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-17`); `RELATION_TYPES` = `AssetTemplates.RELATION_TYPES` (8 типов, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:102-103`) + `HOSTED_ON` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:38`); `SOURCES` и `EVENT_STATUSES` = `SyncSources.CODES`/`STATUSES` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:19-33`).

## 8. Тексты, видимые пользователю (стабильные, на них опираются тесты)

«Вход…», «Не удалось выполнить вход. Обновите страницу.», «Не удалось загрузить конфигурацию консоли.», «Нет прав», «Загрузка…», «Нет данных», «Ничего не найдено», «Результат обрезан лимитом.», «Окрестность показана не полностью: превышен бюджет узлов или связей.», «Записей источников нет», «Событий нет», «Записей DLQ нет», «Операций нет» 🟢 (`access/admin-ui/src/App.tsx:36-39`, `access/admin-ui/src/index.tsx:10`, `access/admin-ui/src/components/ForbiddenScreen.tsx:5`, `access/admin-ui/src/components/common.tsx:28-30`, `access/admin-ui/src/pages/graph/AssetSearch.tsx:56, 59`, `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:41-45`, `access/admin-ui/src/pages/graph/AssetCard.tsx:76`, `access/admin-ui/src/pages/sync/EventsTab.tsx:85`, `access/admin-ui/src/pages/sync/DlqTab.tsx:44`, `access/admin-ui/src/pages/sync/AuditTab.tsx:16`).
