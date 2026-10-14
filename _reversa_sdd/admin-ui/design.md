# access/admin-ui: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md).
> Use case-спецификации: [`oidc-login/`](oidc-login/design.md), [`graph-explorer/`](graph-explorer/design.md), [`sync-monitoring/`](sync-monitoring/design.md), [`identity-audit/`](identity-audit/design.md), [`build-bundle/`](build-bundle/design.md).
> Сквозные части (корень приложения, клиент API, `useAsync`, общие компоненты, типы) описаны здесь и в use case не дублируются.

## Интерфейс

Исходники — `access/admin-ui/src` (React 18.3.1, TypeScript 5.7.2, функциональные компоненты и хуки; глобального хранилища и роутер-библиотеки нет) 🟢 (`access/admin-ui/package.json:11-34`, `access/admin-ui/tsconfig.json:8`).

| Символ | Сигнатура | Результат / поведение | Трассировка |
|--------|-----------|-----------------------|-------------|
| `loadConsoleConfig` | `(fetchFn?) -> Promise<ConsoleConfig>` | `GET /console-config.json`; не-2xx или пустое `issuer`/`clientId`/`scope` → `Error` (не глушится) | `access/admin-ui/src/auth/authService.ts:22-32` 🟢 |
| `createOidcAuthService` | `(ConsoleConfig) -> AuthService` | `init()`, `getAccessToken()`, `login()`, `logout()`; токен в `InMemoryWebStorage` | `access/admin-ui/src/auth/authService.ts:39-80` 🟢 |
| `App` | `({auth}) -> JSX` | состояния `pending`/`ready`/`failed`; `ready` → `Layout` + `ApiProvider key={route}` | `access/admin-ui/src/App.tsx:12-49` 🟢 |
| `useHashRoute` / `parseRoute` | `() -> Route` / `(hash) -> Route` | `'graph' \| 'sync'`; неизвестное → `'graph'` | `access/admin-ui/src/useHashRoute.ts:11-25` 🟢 |
| `createApiClient` | `(ApiClientOptions) -> ApiClient{get, post}` | `fetch` + Bearer; 401 → `onUnauthorized` + `UnauthorizedError`; 403 → `onForbidden` + `ForbiddenError`; прочее не-2xx → `ApiError(status)`; 204 → `undefined` | `access/admin-ui/src/api/apiClient.ts:40-75` 🟢 |
| `ApiProvider` / `useApi` | `({auth, children})` / `() -> ApiClient` | вход при 401 один раз; экран «Нет прав» при 403; `useApi` вне провайдера → `Error` | `access/admin-ui/src/api/ApiContext.tsx:12-45` 🟢 |
| `endpoints` | `(ApiClient) -> {stats, search, node, neighborhood, sources, events, event, dlq, conflicts, candidates, audit}` | типизированные `GET`; query без пустых значений, массивы повторяют ключ | `access/admin-ui/src/api/endpoints.ts:20-72` 🟢 |
| `useAsync` | `(load, deps, refreshMs?) -> {state, reload}` | `loading`/`error`/`ready`; тихое обновление по таймеру; отмена устаревшего ответа | `access/admin-ui/src/hooks/useAsync.ts:19-56` 🟢 |
| `describeError` | `(unknown) -> string` | «Ошибка запроса (HTTP N)» для `ApiError`, иначе «Не удалось выполнить запрос» | `access/admin-ui/src/hooks/useAsync.ts:10-13` 🟢 |
| `Loadable`, `Pager`, `Badge`, `Select`, `Time`, `text`, `toIso` | общие компоненты и функции | загрузка/ошибка/пусто; пагинация; бейдж; фильтр «все»; время в локальной зоне; значение как текст; `datetime-local` → ISO UTC | `access/admin-ui/src/components/common.tsx:5-106` 🟢 |
| `GraphCanvas` | `({data: Neighborhood, onSelect})` | Cytoscape, раскладка `cose`, цвет по метке, центр с рамкой, закрытые узлы с `opacity 0.5`, клик → `gid` | `access/admin-ui/src/components/GraphCanvas.tsx:10-69` 🟢 |
| `Layout`, `ForbiddenScreen` | каркас с навигацией; экран 403 | `<nav aria-label="Разделы">`, ссылки `#/graph`, `#/sync` | `access/admin-ui/src/components/Layout.tsx:5-21`, `access/admin-ui/src/components/ForbiddenScreen.tsx:2-9` 🟢 |

Типы ответов (`access/admin-ui/src/api/types.ts:3-199`) — зеркало Java-records консоли (`GraphResponses`, `Rows`, `Page`); поля — [`contracts.md`](contracts.md) §6.

## Старт приложения

1. `index.tsx`: `loadConsoleConfig()` → `root.render(<App auth={createOidcAuthService(config)} />)`; при отказе — `<p role="alert">Не удалось загрузить конфигурацию консоли.</p>` (`access/admin-ui/src/index.tsx:6-10`) 🟢.
2. `App` (`useEffect` по `auth`): `auth.init()`; `true` → `ready`; `false` → `auth.login()` (редирект на IdP); исключение → `failed` (`access/admin-ui/src/App.tsx:16-33`) 🟢. Флаг `cancelled` защищает от обновления размонтированного компонента (`access/admin-ui/src/App.tsx:17-32`) 🟢.
3. `pending` → `<p>Вход…</p>`; `failed` → `<p role="alert">Не удалось выполнить вход. Обновите страницу.</p>` (`access/admin-ui/src/App.tsx:35-40`) 🟢.
4. `ready` → `Layout` с `ApiProvider key={route}` и страницей по `route` (`access/admin-ui/src/App.tsx:41-48`) 🟢.

Детали входа и обработки 401/403 — [`oidc-login/design.md`](oidc-login/design.md).

## Состояние и загрузка данных

- Глобального стора нет. Серверные данные — `useAsync`; локальные фильтры, страницы и выбранный актив — `useState` страницы (`access/admin-ui/src/pages/GraphPage.tsx:9`, `access/admin-ui/src/pages/sync/EventsTab.tsx:50-55`) 🟢.
- `useAsync`: эффект зависит от `[...deps, tick]`; при смене `deps` показывает «Загрузка…» (`silent=false`), по таймеру (`refreshMs`) — тихое обновление без сброса (`silent=true`); `reload()` увеличивает `tick` (`access/admin-ui/src/hooks/useAsync.ts:24-55`) 🟢.
- 🟡 `NeighborhoodView` передаёт массив `relTypes` в зависимости `useAsync`; новый массив создаётся при каждом переключении чекбокса, поэтому эффект срабатывает ровно на изменение выбора (`access/admin-ui/src/pages/graph/NeighborhoodView.tsx:13-18`).

## Маршруты и экраны

| Hash | Компонент | Содержимое | Спецификация |
|------|-----------|------------|--------------|
| `#/graph` (по умолчанию) | `GraphPage` | `GraphSummary`, `AssetSearch`, при выборе — `AssetCard` + `NeighborhoodView` | [`graph-explorer/`](graph-explorer/design.md) |
| `#/sync` | `SyncPage` | вкладки: «Источники» (по умолчанию), «Журнал событий», «DLQ», «Identity», «Аудит» | [`sync-monitoring/`](sync-monitoring/design.md), [`identity-audit/`](identity-audit/design.md) |

Трассировка: `access/admin-ui/src/useHashRoute.ts:5-8`, `access/admin-ui/src/pages/GraphPage.tsx:8-23`, `access/admin-ui/src/pages/SyncPage.tsx:8-39` 🟢. Вкладки — `<button role="tab" aria-selected>`; состояние вкладки не сохраняется в адресе (после перезагрузки всегда «Источники») 🟢 (`access/admin-ui/src/pages/SyncPage.tsx:18-34`).

## Безопасность клиента

- Аутентификация: Bearer из памяти; `Authorization` не отправляется, если токена нет или он просрочен (`getAccessToken()` → `null`), и сервер отвечает 401 (`access/admin-ui/src/api/apiClient.ts:45-48`, `access/admin-ui/src/auth/authService.ts:76`) 🟢.
- Вывод данных: React экранирует текст; `dangerouslySetInnerHTML` в коде нет (проверено поиском по `access/admin-ui/src`) 🟢.
- CSP: бандл без inline; сервер ставит `default-src 'self'; connect-src 'self' <origin issuer>; frame-ancestors 'none'; base-uri 'self'; object-src 'none'` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:61-66`) 🟢. Запросы UI идут на свой origin (API) и на origin issuer (token endpoint `oidc-client-ts`) 🟡 (адреса token endpoint определяет библиотека по discovery).
- Секретов в бандле нет: `issuer`, `clientId`, `scope` приходят с сервера (`access/admin-ui/src/auth/authService.ts:3-8, 22-32`) 🟢.

## Зависит от / Связанные спецификации

Источник: [`../traceability/spec-impact-matrix.md`](../traceability/spec-impact-matrix.md) §1, §2, §3.

**Прямые зависимости.** В Maven у модуля нет зависимостей от других модулей репозитория; сам `admin-ui` — зависимость `admin-console` (`access/admin-console/pom.xml:22`) 🟢. Матрица §1 строка `access/admin-console` (`_reversa_sdd/traceability/spec-impact-matrix.md:24`): меняется REST-контракт `/api/**`, CSP или `console-config.json` — пересматривается эта спецификация.

**Через данные и протокол (§1):**

| Модуль | Что берёт UI | Спецификация |
|--------|--------------|--------------|
| `access/admin-console` | REST `/api/graph/**`, `/api/sync/**`, `/api/identity/**`, `/api/audit`, `GET /console-config.json`, статика, CSP | серверная сторона — [`../flowcharts/admin-console.md`](../flowcharts/admin-console.md); контракт — [`contracts.md`](contracts.md) |
| `access/graph-query-core` | формы ответов графа (`types.ts`) и allowlist'ы (`ConsoleTemplates`, `AssetTemplates`) (`_reversa_sdd/traceability/spec-impact-matrix.md:22`) | [`../flowcharts/graph-query-core.md`](../flowcharts/graph-query-core.md) (SDD-папки у модуля нет) |
| `ingestion/ingestion-service`, `ingestion/event-journal`, `ingestion/identity-resolution` | данные `inbox_event`, `dlq_entry`, `consumer_checkpoint`, `identity_candidate`, `source_conflict`, `admin_audit` (через консоль) | [`../ingestion-service/design.md`](../ingestion-service/design.md), [`../event-journal/design.md`](../event-journal/design.md), [`../identity-resolution/design.md`](../identity-resolution/design.md) |
| `platform/docker` | стенд: публикация консоли на loopback, клиент Keycloak `archrag-admin-console`, `console-smoke.sh` | [`../docker/design.md`](../docker/design.md), [`../docker/keycloak-oidc/design.md`](../docker/keycloak-oidc/design.md) |

**Неявные связи (§2), затрагивающие модуль:**

| № | Связь | Где в модуле | Защита |
|---|-------|--------------|--------|
| 2 | Таблица кодов источников `eam / scm / cmdb / deploymap` (шесть копий) | `access/admin-ui/src/api/constants.ts:27` ↔ `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:19` | нет теста «покрыты все `SourceSystemCode`» 🟢 [R.8: ответ владельца, вопрос 9] (Q-05) |
| 9 | Allowlist'ы меток и типов связей | `access/admin-ui/src/api/constants.ts:5-25` ↔ `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-17, 102-103`, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:38` | бэкенд отвечает 400 на неизвестное значение; UI тестируется отдельно 🟡 (Q-05) |
| 10 | Порог устаревания 7 суток | `access/admin-ui/src/api/constants.ts:56` ↔ `access/mcp-server/src/main/resources/application.yml:44` (`archrag.query.stale-after: P7D`) | связи нет 🟢 [R.8: ответ владельца, вопрос 9] (Q-04) |
| — | Список статусов события (§3 «Новый статус события») | `access/admin-ui/src/api/constants.ts:29-40` ↔ `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:22-33` | таблица продублирована, теста нет 🟡 (Q-05) |
| 3 | Регистр источника: URN в журнале, верхний регистр в графе, нижний в консоли | UI оперирует коротким нижним регистром (`access/admin-ui/src/api/constants.ts:27`); перевод делает консоль (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:36-37`) | соглашение 🟡 |

Сквозные последствия (матрица §3, строки «Новая метка узла или тип связи», «Новый статус события», «Новый scope или роль»: `_reversa_sdd/traceability/spec-impact-matrix.md:55, 57, 62`): новая метка, тип связи, статус или источник требует правки `access/admin-ui/src/api/constants.ts`; новый scope/роль — пересмотра `scope` в `console-config.json` и клиента Keycloak.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Токен только в памяти (`InMemoryWebStorage`) | `access/admin-ui/src/auth/authService.ts:35-37, 48` | 🟢 |
| Конфигурация OIDC с сервера, а не из бандла | `access/admin-ui/src/auth/authService.ts:3, 22-32`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:27-29` | 🟢 |
| Hash-роутинг без библиотеки (сервер отдаёт SPA fallback, но хэш не требует его) | `access/admin-ui/src/useHashRoute.ts:11-25` | 🟡 (причина выбора в коде не записана) |
| Allowlist'ы продублированы в UI («UI не зависит от Java-модулей»; бэкенд отвергает чужое) | `access/admin-ui/src/api/constants.ts:1-4` | 🟢 |
| CSS выносится в файл (CSP запрещает inline) | `access/admin-ui/webpack.config.js:26-30` | 🟢 |
| `key={route}` у `ApiProvider`: 403 одного раздела не блокирует навигацию | `access/admin-ui/src/App.tsx:43-45` | 🟢 |
| Сборка фронтенда внутри Maven (`frontend-maven-plugin`) | `access/admin-ui/pom.xml:26-78`; `docs/adr/0027-frontend-react-typescript-webpack.md:13-18` | 🟢 |

## Estado Interno

Состояние только в памяти вкладки: `AuthState` (`pending`/`ready`/`failed`), `Route`, `forbidden`/`loginFailed`/`loginStarted` в `ApiProvider`, `user` внутри замыкания `createOidcAuthService`, локальные `useState` фильтров/страниц/выбранного `gid`, `AsyncState` каждого запроса 🟢 (`access/admin-ui/src/App.tsx:13-14`, `access/admin-ui/src/api/ApiContext.tsx:13-15`, `access/admin-ui/src/auth/authService.ts:50`). Постоянных хранилищ (cookie, `localStorage`) UI не использует; `sessionStorage` задействует только библиотека на время редиректа 🟡 (Q-08).

## Riscos e Lacunas

- 🟢 [UNLOCKER-280: реализовано] `health()` в «Источниках»: `RUNNING` — «идёт прогон» (`warn`), `FAILED` или `failed > 0` — `bad`, `SUCCEEDED` без `failed` — `ok`; прогон закрывает `EventProcessor` по маркеру (Q-01). Показывается `endedAt`.
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] Вкладка DLQ по умолчанию просит `replayed=false`, но `dlq_entry.replayed_at` никто не заполняет, поэтому «открытые» = все записи — Q-02.
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)] Фильтр кандидатов `RESOLVED` пуст: `identity_candidate.status` не выходит из `OPEN` — Q-03.
- 🟢 [R.8: ответ владельца, вопрос 9] Порог «устарел» 7 суток не связан с `archrag.query.stale-after`; allowlist'ы продублированы без теста — Q-04, Q-05.
- 🟡 `logout()` не подключён к UI; токен не обновляется тихо — Q-06, Q-07.
- 🟡 Cytoscape под строгой CSP проверяется только в jsdom; реальная проверка — смоук стенда — Q-10.
- 🟡 Локальный запуск `npm start` (порт 3000) не совпадает с redirect URI клиента Keycloak (`127.0.0.1:<порт консоли>/*`) — Q-13.
