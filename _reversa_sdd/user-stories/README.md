# User stories: оглавление

> Истории выведены из реализованного поведения (specs R.6a/R.6b) и критериев `docs/00-vision.md`; требований, которых нет в коде, здесь нет. Трассировка идёт в specs единиц (там `файл:строка`), а не дублируется. Метки: 🟢 подтверждено кодом и тестом, 🟡 выведено, 🔴 пробел (см. `questions.md` единицы).

| Файл | Актор | Единицы |
|------|-------|---------|
| [`ai-agent-asset-discovery.md`](ai-agent-asset-discovery.md) | ИИ-агент (MCP-клиент) | mcp-server, graph-query-core |
| [`operator-ingestion-admin.md`](operator-ingestion-admin.md) | Оператор платформы | ingestion-service, event-journal, identity-resolution |
| [`architect-console.md`](architect-console.md) | Архитектор / аналитик (консоль) | admin-console, admin-ui |
| [`source-owner-integration.md`](source-owner-integration.md) | Владелец мастер-системы | adapter-core, eam/scm/asset/deploymap-adapter, source-spi |
| [`stand-operator.md`](stand-operator.md) | Разработчик/оператор демо-стенда | docker, demo-launcher, source-stubs, integration-tests |
