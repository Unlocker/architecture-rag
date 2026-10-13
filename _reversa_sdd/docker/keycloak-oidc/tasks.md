# keycloak-oidc: задачи

## Pré-requisitos
- [ ] Переменные `.env`: `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_CONSOLE_USER`, `KEYCLOAK_CONSOLE_PASSWORD`, `ARCHRAG_PUBLIC_PORT`, `ARCHRAG_CONSOLE_PORT`
- [ ] Сеть `edge` + `backend` для Keycloak; запись `127.0.0.1 keycloak` в hosts на хосте оператора консоли

## Tarefas

- [ ] T-01: Сервис `keycloak` (`start-dev --import-realm`, `KC_HOSTNAME`, `KC_HTTP_ENABLED`, `KC_HEALTH_ENABLED`, порт на loopback, healthcheck через `/dev/tcp`)
  - Origin: `platform/docker/docker-compose.yml:137-175`
  - Критерий готовности: healthy; realm `archrag` импортирован; iss токенов `http://keycloak:8080/realms/archrag`
  - Уверенность: 🟢
- [ ] T-02: Client scopes `architecture.read` и `architecture.admin` (mapper `admin-audience`, `include.in.token.scope`)
  - Origin: `platform/docker/keycloak/archrag-realm.json:5-29`
  - Критерий готовности: AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-03: Клиент `archrag-demo` (confidential, service account, default `architecture.read`, optional `architecture.admin`, mapper `mcp-audience`)
  - Origin: `platform/docker/keycloak/archrag-realm.json:50-75`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-04: Клиент `archrag-admin-console` (public, standard flow, PKCE S256, redirect/web origin по `ARCHRAG_CONSOLE_PORT`, optional scope'ы, mapper'ы `console-audience` и `sub`)
  - Origin: `platform/docker/keycloak/archrag-realm.json:76-111`
  - Критерий готовности: AC-04, AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-05: Роль `archrag-console-admin` и демо-пользователь консоли
  - Origin: `platform/docker/keycloak/archrag-realm.json:30-48`
  - Критерий готовности: вход демо-пользователя проходит; роль присвоена (проверка роли в сервисах не реализована — Q-04)
  - Уверенность: 🟢 / 🟡 (роль)
- [ ] T-06: Проксирование token endpoint через nginx (без админки Keycloak)
  - Origin: `platform/docker/proxy/nginx.conf:35-40`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-07: Согласованность audience-переменных между Keycloak, `mcp-server`, `ingestion`, `admin-console`
  - Origin: `platform/docker/docker-compose.yml:150-153, 187, 232, 283`
  - Критерий готовности: значения совпадают ([`../contracts.md`](../contracts.md) §5)
  - Уверенность: 🟢 (автоматической сверки нет)

## Tarefas de Teste

- [ ] TT-01: `smoke.sh` (AC-01..AC-03) — в CI
- [ ] TT-02: `console-smoke.sh` (AC-04..AC-06) — 🟢 [R.8: ответ владельца, вопрос 14] не в CI (Q-07)
- [ ] TT-03: 🟡 нет проверки токена консоли на `/mcp` и проверки отказа при смене redirect URI

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-07 (🟢 [R.8: ответ владельца, вопрос 14]); Q-04, Q-09, Q-15, Q-20 (🟡).
