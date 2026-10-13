# oidc-login: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §2, §3. Сторона Keycloak: [`../../docker/keycloak-oidc/requirements.md`](../../docker/keycloak-oidc/requirements.md).

## Visão Geral

Оператор открывает консоль на loopback; UI читает публичную конфигурацию, завершает или начинает вход по Authorization Code + PKCE, хранит токен в памяти и подставляет его в каждый `GET`. На 401 вход перезапускается (один раз на пачку параллельных ответов), на 403 раздел показывает «Нет прав» 🟢 (`access/admin-ui/src/index.tsx:8-10`, `access/admin-ui/src/App.tsx:16-33`, `access/admin-ui/src/api/apiClient.ts:43-69`).

## Regras de Negócio

- Приложение не рендерится, пока не загружен `/console-config.json` с непустыми `issuer`, `clientId`, `scope`; ошибка не глушится: показывается «Не удалось загрузить конфигурацию консоли.» 🟢 (`access/admin-ui/src/auth/authService.ts:22-32`; `access/admin-ui/src/index.tsx:8-10`).
- Если в адресе есть `code` и `state`, они обрабатываются как redirect-ответ IdP и в любом случае удаляются из адреса (`history.replaceState`), иначе перезагрузка повторила бы ошибку 🟢 (`access/admin-ui/src/auth/authService.ts:54-58`).
- Ошибка `signinRedirectCallback` (просроченный или потерянный `state`) не фатальна: пользователь сбрасывается, `init()` возвращает `false`, `App` начинает вход заново 🟢 (`access/admin-ui/src/auth/authService.ts:59-66`; `access/admin-ui/src/App.tsx:20-25`).
- Перед редиректом на IdP в `state` сохраняется текущий `window.location.hash`; после успешного входа, если `user.state` начинается с `#/`, маршрут восстанавливается 🟢 (`access/admin-ui/src/auth/authService.ts:68-70, 77`).
- Токен считается действительным, только пока пользователь есть и не `expired`; иначе `getAccessToken()` → `null` и запрос уходит без `Authorization` 🟢 (`access/admin-ui/src/auth/authService.ts:74, 76`; `access/admin-ui/src/api/apiClient.ts:45-48`).
- 401 → `onUnauthorized`: первый запуск `auth.login()`, повторные игнорируются, пока флаг `loginStarted` не сброшен (сбрасывается только при ошибке `login()`) 🟢 (`access/admin-ui/src/api/ApiContext.tsx:15, 20-28`).
- Ошибка `login()` → «Не удалось выполнить вход. Обновите страницу.» 🟢 (`access/admin-ui/src/api/ApiContext.tsx:24-27, 33-35`).
- 403 → `ForbiddenScreen` («Нет прав») вместо содержимого раздела; повторного входа нет 🟢 (`access/admin-ui/src/api/ApiContext.tsx:29, 36`; `access/admin-ui/src/components/ForbiddenScreen.tsx:2-9`).
- `logout()` существует, но не вызывается UI 🟡 (Q-06).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Загрузка и валидация `/console-config.json` | Must | AC-01, AC-02 |
| RF-02 | Завершение входа по `code`/`state`, очистка адреса, восстановление hash | Must | AC-03 |
| RF-03 | Старт входа при отсутствии действительного токена | Must | AC-01 |
| RF-04 | Bearer в запросах, 401 → повторный вход один раз | Must | AC-04 |
| RF-05 | 403 → экран «Нет прав» без повторного входа | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Токен только в `InMemoryWebStorage`; перезагрузка вкладки = новый вход | `access/admin-ui/src/auth/authService.ts:48` | 🟢 |
| Безопасность | PKCE, публичный клиент, redirect только на свой origin | `access/admin-ui/src/auth/authService.ts:40, 44, 46`; `platform/docker/keycloak/archrag-realm.json:77-90` | 🟢 |
| Надёжность | Параллельные 401 не порождают несколько редиректов | `access/admin-ui/src/api/ApiContext.tsx:20-28`; `access/admin-ui/src/api/api.test.tsx:95-116` | 🟢 |
| Безопасность | Сообщения об ошибке входа не содержат деталей IdP | `access/admin-ui/src/App.tsx:36`; `access/admin-ui/src/api/ApiContext.tsx:34` | 🟢 |
| Эксплуатация | Вход работает при открытии по `http://127.0.0.1:<порт>` (secure context для PKCE), не по `localhost`; Keycloak доступен браузеру как `keycloak` (запись в hosts) | `platform/docker/README.md:121-122` | 🟡 (требование из README, в коде UI не проверяется) |

## Критерии приёмки

```gherkin
# AC-01 (App.test.tsx «renders layout…», «starts login when there is no token»)
Дано /console-config.json отдаёт issuer, clientId, scope, а в памяти действительного пользователя нет
Когда загружается приложение
Тогда auth.login() вызван ровно один раз (редирект на IdP), навигация не показана
Дано пользователь есть и не просрочен
Когда загружается приложение
Тогда показан Layout со ссылками «Граф» и «Синхронизация»

# AC-02 (authService.test.ts «rejects incomplete config», «rejects HTTP error»)
Дано /console-config.json отвечает 500 или JSON без clientId
Когда loadConsoleConfig завершается
Тогда выбрасывается ошибка («HTTP 500» / «нужны issuer, clientId и scope»), а index.tsx показывает «Не удалось загрузить конфигурацию консоли.»

# AC-03 (authService.test.ts «successful callback restores hash route», «callback with stale state…»)
Дано адрес /?code=abc&state=s, signinRedirectCallback вернул пользователя со state «#/sync»
Когда вызван init()
Тогда init() = true, в адресе нет code/state, hash = «#/sync»
Дано signinRedirectCallback отклонён («No matching state»)
Когда вызван init()
Тогда init() = false, code/state удалены из адреса, hash сохранён, App запускает вход заново

# AC-04 (api.test.tsx «sends Bearer…», «401 triggers re-login», «parallel 401 start login once», «ApiProvider shows error when login fails»)
Дано токен «abc»
Когда выполняется api.get('/api/x')
Тогда заголовок Authorization = «Bearer abc»
Дано два параллельных запроса получили 401
Когда сработал onUnauthorized
Тогда auth.login() вызван один раз
Дано login() отклонён
Когда ответ 401
Тогда показано «Не удалось выполнить вход. Обновите страницу.»

# AC-05 (api.test.tsx «403 is reported as forbidden…», «ApiProvider shows the 403 screen»)
Дано сервер ответил 403
Когда ApiProvider получил ForbiddenError
Тогда показан «Нет прав», содержимое раздела скрыто, auth.login() не вызывался
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Без токена `/api/**` недоступен (scope `architecture.admin`) |
| Кнопка «Выйти» | Won't | Не реализована (Q-06) |
| Тихое обновление токена | Won't | Не реализовано (Q-07) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-ui/src/auth/authService.ts` | `loadConsoleConfig`, `createOidcAuthService` | 🟢 `access/admin-ui/src/auth/authService.test.ts` |
| `access/admin-ui/src/api/apiClient.ts`, `access/admin-ui/src/api/ApiContext.tsx` | `createApiClient`, `ApiProvider` | 🟢 `access/admin-ui/src/api/api.test.tsx` |
| `access/admin-ui/src/App.tsx`, `access/admin-ui/src/index.tsx` | `App`, точка входа | 🟢 `access/admin-ui/src/App.test.tsx` |
| `access/admin-ui/src/components/ForbiddenScreen.tsx` | `ForbiddenScreen` | 🟢 |
