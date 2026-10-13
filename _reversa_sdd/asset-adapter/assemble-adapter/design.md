# assemble-adapter: дизайн

Реализация и интерфейс — в [`../design.md`](../design.md), раздел «Интерфейс» и «Основной поток (`create`)». Здесь только последовательность.

1. `config(uri, secret)` → `AdapterConfig.of(CMDB, ...)`: `pageSize=100`, `pollInterval=30 с`, `replayWindow=5 мин`, `reconcileInterval=6 ч`, `RetryPolicy(5, 500 мс, 30 с)` 🟢 (`adapters/adapter-core/.../AdapterConfig.java:60-63`).
2. `create(config, journal, rawStore)` → проверка `config.system() != CMDB` (`AssetAdapter.java:34`) → `new SourceAdapter(...)` (`:37`) → внутри создаются `HttpSourceConnector(CMDB, uri)`, `WebhookHandler`, `Poller` (`SourceAdapter.java:29-31,44-45`) 🟢.
3. Жизненным циклом управляет вызывающий: `startWebhook(InetSocketAddress)`, `startPolling()`, `startReconciliation()`, `close()` (закрывает коннектор, scheduler'ы, webhook-сервер) 🟢 (`SourceAdapter.java:61,72,97,118-136`).

Пример использования: `DemoLauncher.java:142` (`case CMDB -> AssetAdapter.create(config, journal, store)`) 🟢.

Риски: `null` в аргументах не проверяются 🟡.
