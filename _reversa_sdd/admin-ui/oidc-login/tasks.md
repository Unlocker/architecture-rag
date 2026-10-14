# oidc-login: задачи

## Pré-requisitos
- [ ] Публичный `GET /console-config.json`; клиент Keycloak `archrag-admin-console` (PKCE S256, redirect `http://127.0.0.1:<порт>/*`)
- [ ] `oidc-client-ts` 3.1.0 в зависимостях (`access/admin-ui/package.json:13`)

## Tarefas

- [ ] T-01: `ConsoleConfig` и `loadConsoleConfig` (не-2xx и неполная конфигурация → ошибка)
  - Origin: `access/admin-ui/src/auth/authService.ts:3-32`
  - Критерий готовности: AC-02; `access/admin-ui/src/auth/authService.test.ts:22-34`
  - Уверенность: 🟢
- [ ] T-02: `createOidcAuthService` (`UserManager` Code + PKCE, `InMemoryWebStorage`, `init`, `getAccessToken`, `login` со `state = hash`, `logout`)
  - Origin: `access/admin-ui/src/auth/authService.ts:39-80`
  - Критерий готовности: AC-01, AC-03; `access/admin-ui/src/auth/authService.test.ts:36-80`
  - Уверенность: 🟢
- [ ] T-03: `createApiClient`: Bearer, `ApiError`/`UnauthorizedError`/`ForbiddenError`, 204
  - Origin: `access/admin-ui/src/api/apiClient.ts:1-75`
  - Критерий готовности: AC-04, AC-05; `access/admin-ui/src/api/api.test.tsx:19-44`
  - Уверенность: 🟢
- [ ] T-04: `ApiProvider` (один вход на пачку 401, `loginFailed`, `forbidden`) и `useApi`
  - Origin: `access/admin-ui/src/api/ApiContext.tsx:12-45`
  - Критерий готовности: AC-04, AC-05; `access/admin-ui/src/api/api.test.tsx:69-130`
  - Уверенность: 🟢
- [ ] T-05: Корень: состояния `pending/ready/failed`, запуск входа, `key={route}`
  - Origin: `access/admin-ui/src/App.tsx:9-49`; `access/admin-ui/src/index.tsx:6-10`
  - Критерий готовности: AC-01; `access/admin-ui/src/App.test.tsx:18-49`
  - Уверенность: 🟢
- [ ] T-06: `ForbiddenScreen`
  - Origin: `access/admin-ui/src/components/ForbiddenScreen.tsx:2-9`
  - Критерий готовности: AC-05
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `access/admin-ui/src/auth/authService.test.ts` (7 тестов), `access/admin-ui/src/api/api.test.tsx` (7), `access/admin-ui/src/App.test.tsx` (4)
- [ ] TT-02: 🔴 нет теста на цикл входа при `expired` пользователе после callback и на тихое обновление (Q-07)
- [ ] TT-03: 🟡 нет e2e-проверки в браузере; поток Code + PKCE на стенде проверяет `platform/docker/console-smoke.sh:22-51` (curl, не браузер), в CI он не запускается (Q-10)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-06 → T-05.

## Lacunas Pendentes (🔴)
Явных 🟡 для use case нет (метка R.8 п. 16 закрыта: 🟢 (реализовано — UNLOCKER-288)); 🟡 — [`../questions.md`](../questions.md) Q-06, Q-07, Q-08, Q-13, Q-14.
