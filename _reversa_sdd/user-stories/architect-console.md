# User stories: архитектор работает в консоли

> Основание: [`../admin-console/requirements.md`](../admin-console/requirements.md), [`../admin-ui/requirements.md`](../admin-ui/requirements.md). OpenAPI: [`../openapi/admin-console.yaml`](../openapi/admin-console.yaml). Консоль только читает (ADR 0026).

## US-C1. Войти через OIDC
Как архитектор, я хочу войти через Keycloak (PKCE), чтобы SPA получила токен со scope `architecture.admin`; токен хранится в памяти 🟢. Без токена `/api/**` отвечает 401, без scope — 403 🟢. Спеки: [`../admin-ui/oidc-login/`](../admin-ui/oidc-login/requirements.md), [`../admin-console/auth-and-spa/`](../admin-console/auth-and-spa/requirements.md).

## US-C2. Искать актив и видеть его окрестность
Как архитектор, я хочу искать активы, открывать карточку с источниками и окрестность глубиной 1..2 с фильтром типов связей 🟢. Спеки: [`../admin-console/graph-browse/`](../admin-console/graph-browse/requirements.md), [`../admin-ui/graph-explorer/`](../admin-ui/graph-explorer/requirements.md).

## US-C3. Следить за синхронизацией
Как оператор данных, я хочу видеть состояние четырёх источников, журнал событий и DLQ с фильтрами и пагинацией, чтобы найти застрявшие события 🟢. 🔴 «Светофор» источников опирается на `SyncRun FAILED`, которого проектор не пишет (Q-01 `../admin-ui/questions.md`); фильтр DLQ `replayed` бесполезен (Q-02). Спеки: [`../admin-console/sync-journal/`](../admin-console/sync-journal/requirements.md), [`../admin-ui/sync-monitoring/`](../admin-ui/sync-monitoring/requirements.md).

## US-C4. Разбирать конфликты и кандидатов идентичности
Как архитектор, я хочу видеть конфликты источников и кандидатов на слияние, а также аудит админских операций 🟢. 🔴 Статус кандидатов не выходит из `OPEN` (Q-03). Спеки: [`../admin-console/identity-audit/`](../admin-console/identity-audit/requirements.md), [`../admin-ui/identity-audit/`](../admin-ui/identity-audit/requirements.md).

## US-C5. Не изменить данные случайно
Как владелец платформы, я хочу, чтобы консоль не имела записывающих ручек, читала PostgreSQL ролью `archrag_console_ro` и была опубликована только на loopback 🟢 (🔴 привязка к loopback лишь в Compose, Q-01 `../admin-console/questions.md`).
