# create-adapter, дизайн

Модульный дизайн: [../design.md](../design.md).

1. `config`: `AdapterConfig.of(SCM, uri, secret)` (`ScmAdapter.java:23-25`).
2. `create`: guard `system != SCM` → `IllegalArgumentException` с текстом `"config is for " + config.system() + ", expected " + SYSTEM` (`ScmAdapter.java:34-36`) 🟢. Поскольку `AdapterConfig.toString` скрывает секрет, а в сообщение попадает только `config.system()`, секрет не утекает 🟢 (`AdapterConfig.java:70-73`).
3. `new SourceAdapter(config, journal, rawStore)` (`:37`) создаёт `HttpSourceConnector`, `WebhookHandler(…, Clock.systemUTC())`, `Poller.create` (`SourceAdapter.java:29-46`) 🟢. Запуска фоновых задач `create` не делает 🟢.
4. Закрытие: `SourceAdapter.close()` останавливает reconciler, scheduler, webhook-сервер и коннектор (`SourceAdapter.java:118-136`) 🟢.
