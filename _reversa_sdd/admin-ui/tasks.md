# access/admin-ui: задачи

> Задачи use case: [`oidc-login/`](oidc-login/tasks.md), [`graph-explorer/`](graph-explorer/tasks.md), [`sync-monitoring/`](sync-monitoring/tasks.md), [`identity-audit/`](identity-audit/tasks.md), [`build-bundle/`](build-bundle/tasks.md). Здесь — сквозные части. Задачи описывают, как воспроизвести модуль «с нуля»; чекбоксы не отмечены, потому что код уже существует.

## Pré-requisitos
- [ ] Родительский `pom.xml` модуля `access` и корневой `pom.xml` с `frontend-maven-plugin.version` (`access/admin-ui/pom.xml:7-12`)
- [ ] Доступ к registry npm при первой сборке (Node и npm скачивает плагин в `target/`, `access/admin-ui/pom.xml:32`)
- [ ] Сервер `admin-console` с REST из [`contracts.md`](contracts.md) §1 и `GET /console-config.json`
- [ ] Клиент Keycloak `archrag-admin-console` (публичный, PKCE S256) — [`../docker/keycloak-oidc/requirements.md`](../docker/keycloak-oidc/requirements.md)

## Tarefas

- [ ] T-01: Типы ответов API (`Page`, `Stats`, `NodeCard`, `Neighborhood`, `SourceStatus`, `SyncEvent`, `DlqEntry`, `Conflict`, `Candidate`, `Audit` и др.)
  - Origin: `access/admin-ui/src/api/types.ts:3-199`
  - Критерий готовности: поля соответствуют [`contracts.md`](contracts.md) §6; `tsc` в режиме `strict` без ошибок (`access/admin-ui/tsconfig.json:8-11`)
  - Уверенность: 🟢
- [ ] T-02: Константы UI (allowlist'ы, пороги, период автообновления, палитра)
  - Origin: `access/admin-ui/src/api/constants.ts:5-63`
  - Критерий готовности: значения равны [`contracts.md`](contracts.md) §7 и бэкенду; расхождение ловится тестом — теста сейчас нет (Q-05)
  - Уверенность: 🟢 (значения) / 🔴 (защита от дрейфа)
- [ ] T-03: HTTP-клиент с Bearer и ошибками `ApiError`/`UnauthorizedError`/`ForbiddenError`
  - Origin: `access/admin-ui/src/api/apiClient.ts:1-75`
  - Критерий готовности: AC `oidc-login` AC-04, AC-05; `access/admin-ui/src/api/api.test.tsx:19-44`
  - Уверенность: 🟢
- [ ] T-04: `ApiProvider`/`useApi` (один вход на параллельные 401, экран 403, ошибка входа)
  - Origin: `access/admin-ui/src/api/ApiContext.tsx:12-45`
  - Критерий готовности: `access/admin-ui/src/api/api.test.tsx:69-130`
  - Уверенность: 🟢
- [ ] T-05: Типизированные вызовы `endpoints` и `query()`
  - Origin: `access/admin-ui/src/api/endpoints.ts:17-74`
  - Критерий готовности: 11 вызовов из [`contracts.md`](contracts.md) §1 (№ 2..12), пустые параметры не попадают в query, массивы повторяют ключ
  - Уверенность: 🟢 (юнит-теста `query()` отдельно нет; проверяется через страницы)
- [ ] T-06: Хук `useAsync` и `describeError`
  - Origin: `access/admin-ui/src/hooks/useAsync.ts:9-56`
  - Критерий готовности: состояния `loading/ready/error`, тихое автообновление, отмена устаревшего ответа; сообщение об ошибке без тела ответа (AC-03 в [`requirements.md`](requirements.md))
  - Уверенность: 🟢
- [ ] T-07: Общие компоненты (`Time`, `Loadable`, `Pager`, `Badge`, `Select`, `text`, `toIso`)
  - Origin: `access/admin-ui/src/components/common.tsx:5-106`
  - Критерий готовности: `Pager` блокирует «Назад» на странице 0 и «Вперёд» на последней; `toIso('')` → `undefined`
  - Уверенность: 🟢
- [ ] T-08: Каркас: `index.tsx`, `App`, `Layout`, `ForbiddenScreen`, `useHashRoute`
  - Origin: `access/admin-ui/src/index.tsx:6-10`; `access/admin-ui/src/App.tsx:12-49`; `access/admin-ui/src/components/Layout.tsx:5-21`; `access/admin-ui/src/components/ForbiddenScreen.tsx:2-9`; `access/admin-ui/src/useHashRoute.ts:5-25`
  - Критерий готовности: AC-01, AC-01n ([`requirements.md`](requirements.md)); `access/admin-ui/src/App.test.tsx:18-47`
  - Уверенность: 🟢
- [ ] T-09: Разделы «Граф» и «Синхронизация» — по use case ([`graph-explorer/tasks.md`](graph-explorer/tasks.md), [`sync-monitoring/tasks.md`](sync-monitoring/tasks.md), [`identity-audit/tasks.md`](identity-audit/tasks.md))
  - Origin: `access/admin-ui/src/pages/GraphPage.tsx:8-23`; `access/admin-ui/src/pages/SyncPage.tsx:8-39`
  - Критерий готовности: критерии use case выполнены
  - Уверенность: 🟢
- [ ] T-10: Вход OIDC и загрузка конфигурации — по [`oidc-login/tasks.md`](oidc-login/tasks.md)
  - Origin: `access/admin-ui/src/auth/authService.ts:3-80`
  - Критерий готовности: AC `oidc-login`
  - Уверенность: 🟢
- [ ] T-11: Сборка и тесты в Maven — по [`build-bundle/tasks.md`](build-bundle/tasks.md)
  - Origin: `access/admin-ui/pom.xml:26-78`; `access/admin-ui/webpack.config.js:11-47`
  - Критерий готовности: AC `build-bundle`
  - Уверенность: 🟢
- [ ] T-12: Стили
  - Origin: `access/admin-ui/src/styles.css:1-20`
  - Критерий готовности: CSS извлекается в отдельный файл (нет inline-стилей), классы `.banner`, `.badge.ok/.warn/.bad`, `.graph-canvas { height: 480px }`, `[role='tab'][aria-selected='true']` определены
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `access/admin-ui/src/api/api.test.tsx` — Bearer и JSON, 401 → вход, 403 → «Нет прав» без повторного входа, параллельные 401, ошибка входа
- [ ] TT-02: `access/admin-ui/src/auth/authService.test.ts` — конфигурация (валидная, неполная, HTTP-ошибка), `init` без/с кодом, устаревший `state`, восстановление hash
- [ ] TT-03: `access/admin-ui/src/App.test.tsx`, `access/admin-ui/src/useHashRoute.test.ts` — навигация, вход при отсутствии токена, ошибка входа, неизвестный hash
- [ ] TT-04: `access/admin-ui/src/pages/GraphPage.test.tsx` (17 тестов), `access/admin-ui/src/pages/SyncPage.test.tsx` (16 тестов) — см. use case
- [ ] TT-05: 🟢 [R.8: ответ владельца, вопрос 9] нет теста на дрейф констант и типов относительно бэкенда (Q-05) и на отсутствие не-`GET` вызовов (Q-11)
- [x] TT-06: 🟢 [UNLOCKER-280: реализовано] ветки светофора (`RUNNING` → warn, `FAILED` → bad, `SUCCEEDED` без `failed` → ok) покрыты в `SyncPage.test.tsx`; закрытие прогона на реальных данных — `SyncRunLifecycleIT`, `SyncConsoleIT`

## Tarefas de Migração de Dados

n/a: UI не владеет данными; формат ответов определяет `admin-console`.

## Ordem Sugerida
1. T-01, T-02 → 2. T-03, T-04, T-05 → 3. T-06, T-07 → 4. T-10 → 5. T-08 → 6. T-09 (use case) → 7. T-12, T-11.

## Lacunas Pendentes (🔴)
См. [`questions.md`](questions.md): Q-01..Q-05, Q-12, Q-19 (🔴); Q-06..Q-11, Q-13..Q-18 (🟡).
