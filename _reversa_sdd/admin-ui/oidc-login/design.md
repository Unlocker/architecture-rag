# oidc-login: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §2, §3. Блок-схемы: [`../../flowcharts/admin-ui.md`](../../flowcharts/admin-ui.md).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `loadConsoleConfig` | `(fetchFn = fetch) -> Promise<ConsoleConfig>` | `GET /console-config.json` с `Accept: application/json`; ошибка при не-2xx и при пустых полях | `access/admin-ui/src/auth/authService.ts:22-32` 🟢 |
| `AuthService.init` | `() -> Promise<boolean>` | `true`, если есть действительный пользователь | `access/admin-ui/src/auth/authService.ts:53-75` 🟢 |
| `AuthService.getAccessToken` | `() -> string \| null` | токен или `null` при отсутствии/просрочке | `access/admin-ui/src/auth/authService.ts:76` 🟢 |
| `AuthService.login` | `() -> Promise<void>` | `signinRedirect({state: window.location.hash})` | `access/admin-ui/src/auth/authService.ts:77` 🟢 |
| `AuthService.logout` | `() -> Promise<void>` | `signoutRedirect()`; не используется UI | `access/admin-ui/src/auth/authService.ts:78` 🟡 |
| `ApiProvider` | `({auth, children})` | клиент с `getToken`, `onUnauthorized`, `onForbidden` | `access/admin-ui/src/api/ApiContext.tsx:12-37` 🟢 |

## Fluxo Principal

1. `index.tsx` вызывает `loadConsoleConfig()`; при успехе создаёт `createOidcAuthService(config)` и рендерит `App` (`access/admin-ui/src/index.tsx:8-10`).
2. `createOidcAuthService`: `redirectUri = origin + "/"`; `UserManager` с `authority = issuer`, `client_id`, `redirect_uri`, `post_logout_redirect_uri`, `response_type = 'code'`, `scope`, `userStore` на `InMemoryWebStorage` (`access/admin-ui/src/auth/authService.ts:39-49`).
3. `App` → `auth.init()`. Если `?code` и `?state` в адресе: `signinRedirectCallback()`, затем `clean()` (`replaceState` без query, hash сохраняется), при `user.state` вида `#/…` — `window.location.hash = user.state`. Иначе `manager.getUser()` (`access/admin-ui/src/auth/authService.ts:53-75`).
4. `init()` возвращает `user !== null && !user.expired`. `true` → `ready`; `false` → `auth.login()` (страница уходит на IdP) (`access/admin-ui/src/App.tsx:20-26`).
5. Дальше каждый `ApiClient.get` берёт токен через `getToken()`; без токена `Authorization` не отправляется (`access/admin-ui/src/api/apiClient.ts:45-48`).
6. Ответ 401: `ApiProvider.onUnauthorized` ставит `loginStarted = true` и вызывает `auth.login()`; `UnauthorizedError` пробрасывается вызывающему (`access/admin-ui/src/api/ApiContext.tsx:20-28`; `access/admin-ui/src/api/apiClient.ts:57-60`).

## Fluxos Alternativos

- `loadConsoleConfig` бросил исключение → `<p role="alert">Не удалось загрузить конфигурацию консоли.</p>`, `App` не создаётся 🟢 (`access/admin-ui/src/index.tsx:10`).
- `init()` бросил исключение → `AuthState = 'failed'` → «Не удалось выполнить вход. Обновите страницу.» 🟢 (`access/admin-ui/src/App.tsx:27-29, 35-37`).
- `signinRedirectCallback` отклонён → `user = null`, `clean()`, `init()` = `false` → новый вход 🟢 (`access/admin-ui/src/auth/authService.ts:59-66`).
- `auth.login()` отклонён из `onUnauthorized` → `loginStarted = false`, `loginFailed = true`, провайдер рисует сообщение вместо содержимого 🟢 (`access/admin-ui/src/api/ApiContext.tsx:24-27, 33-35`).
- Ответ 403 → `forbidden = true`, провайдер показывает `ForbiddenScreen`; повторный вход не запускается; смена раздела пересоздаёт провайдер (`key={route}`) и сбрасывает флаг 🟢 (`access/admin-ui/src/api/ApiContext.tsx:29, 36`; `access/admin-ui/src/App.tsx:43-44`).
- Токен просрочился между запросами → запрос без `Authorization` → 401 → редирект на IdP; несохранённое состояние экрана теряется 🟡 (Q-07).
- 🟡 Если после успешного callback пользователь уже `expired` (рассинхрон часов), `init()` вернёт `false`, и `App` снова запустит вход — защиты от цикла в коде нет (выведено по `access/admin-ui/src/auth/authService.ts:74` и `access/admin-ui/src/App.tsx:20-25`).

## Dependências

`oidc-client-ts` 3.1.0 (`UserManager`, `WebStorageStateStore`, `InMemoryWebStorage`, `User`) (`access/admin-ui/package.json:13`; `access/admin-ui/src/auth/authService.ts:1`); публичный `GET /console-config.json` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:27-29`); клиент Keycloak `archrag-admin-console` (`platform/docker/keycloak/archrag-realm.json:76-111`). Спецификации: [`../../docker/keycloak-oidc/design.md`](../../docker/keycloak-oidc/design.md).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Токены и пользователь в памяти, не в Web Storage | `access/admin-ui/src/auth/authService.ts:35-37, 48` | 🟢 |
| `state` = текущий hash, чтобы вернуть пользователя в раздел | `access/admin-ui/src/auth/authService.ts:68-70, 77` | 🟢 |
| `code`/`state` чистятся при любом исходе callback | `access/admin-ui/src/auth/authService.ts:56-58` | 🟢 |
| Один запуск входа на пачку 401 (`useRef`, не `useState`) | `access/admin-ui/src/api/ApiContext.tsx:15, 22-23` | 🟢 |

## Riscos e Lacunas

- 🟡 Нет выхода из системы и тихого обновления токена — Q-06, Q-07.
- 🟡 Хранилище `state`/`code_verifier` явно не задано — Q-08.
- 🟡 Dev-сервер (порт 3000) не совпадает с redirect URI клиента Keycloak — Q-13.
- 🟡 Любой пользователь realm получает токен консоли — Q-14.
