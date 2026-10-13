# User stories: запуск и проверка демо-стенда

> Основание: [`../docker/requirements.md`](../docker/requirements.md), [`../demo-launcher/requirements.md`](../demo-launcher/requirements.md), [`../source-stubs/requirements.md`](../source-stubs/requirements.md), [`../integration-tests/requirements.md`](../integration-tests/requirements.md).

## US-D1. Поднять стенд одной командой
Как разработчик, я хочу поднять Compose-стенд (Neo4j, PostgreSQL, SeaweedFS, Keycloak, ingestion, адаптеры, MCP, консоль, reverse proxy) с секретами из Docker secrets, чтобы получить рабочую витрину локально 🟢. Наружу публикуются только loopback-порты 🟢 (🔴 описание в README расходится, Q-02 `../docker/questions.md`). Спеки: [`../docker/stand-startup/`](../docker/stand-startup/requirements.md), [`../docker/network-secrets/`](../docker/network-secrets/requirements.md).

## US-D2. Получить токены и пройти OIDC
Как разработчик, я хочу готовый realm Keycloak с клиентами и scope, чтобы вызвать MCP и консоль с настоящими токенами 🟢. Спеки: [`../docker/keycloak-oidc/`](../docker/keycloak-oidc/requirements.md).

## US-D3. Эмулировать источники
Как разработчик, я хочу заглушки источников (`/changes`, `/objects/{type}/{id}`, eam-api) и управляющие ручки demo-launcher (`/control/upsert`, `/control/archive`), чтобы вручную менять данные и видеть эффект в графе 🟢. Спеки: [`../source-stubs/`](../source-stubs/requirements.md), [`../demo-launcher/`](../demo-launcher/requirements.md).

## US-D4. Доказать критерии готовности
Как владелец, я хочу набор `*IT` (F1–F4, синхронизация, проекция, безопасность консоли), чтобы подтверждать критерии PoC в CI 🟢. 🔴 Критерий backup/restore и часть негативов безопасности тестами не покрыты (Q-01, Q-02 `../integration-tests/questions.md`). Спеки: [`../integration-tests/`](../integration-tests/requirements.md).

## US-D5. Резервная копия и наблюдаемость
Как оператор, я хочу сценарии backup/restore и профиль observability (метрики, Grafana) 🟡. Спеки: [`../docker/backup-restore/`](../docker/backup-restore/requirements.md), [`../docker/observability-measure/`](../docker/observability-measure/requirements.md).
