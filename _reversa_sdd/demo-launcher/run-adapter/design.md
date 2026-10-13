# run-adapter: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (диспетчер аргументов, завершение, сборка). Контракты: [`../contracts.md`](../contracts.md) §2.1, §3.1. Блок-схема: [`../../flowcharts/demo-launcher.md`](../../flowcharts/demo-launcher.md) («Старт адаптера `runAdapter`»).

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `DemoLauncher.runAdapter` | `(LauncherConfig.Adapter) -> void throws IOException, InterruptedException` | блокируется до SIGTERM/SIGINT | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:108-135 🟢 |
| `DemoLauncher.create` | `(AdapterConfig, EamAdapter.EamApi, PostgresEventJournal, S3RawPayloadStore) -> SourceAdapter` | `switch` по `config.system()` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:137-145 🟢 |
| `DemoLauncher.healthServer` | `(int, AtomicBoolean) -> HttpServer` | запущенный сервер с контекстом `/health` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160 🟢 |
| `LauncherConfig.adapter` | `(SourceSystem, Map<String,String>) -> Adapter` | `IllegalArgumentException` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:107-125 🟢 |
| `LauncherConfig.Adapter` | record(`system,pgUrl,pgUser,pgPassword,s3Endpoint,s3AccessKey,s3SecretKey,s3Bucket,sourceUrl,webhookSecret,webhookPort,healthPort,pollInterval,reconcileInterval,eamApi`) | `toString` без секретов | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:49-71 🟢 |
| `SourceAdapter` (`startWebhook`, `startPolling`, `startReconciliation`, `close`) | интерфейс `adapter-core` | | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/SourceAdapter.java` 🟡 (сигнатуры вне модуля) |

## Fluxo Principal

1. `PGSimpleDataSource` (URL, пользователь, пароль) → `new PostgresEventJournal(ds)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:109-113) 🟢.
2. `AdapterConfig(system, sourceUrl, webhookSecret, DEFAULT_REPLAY_WINDOW, 100, pollInterval, reconcileInterval, RetryPolicy.defaults())` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:114-116) 🟢.
3. `S3RawPayloadStore.create(endpoint, accessKey, secretKey, bucket)` в `try-with-resources`; `store.ensureBucket()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:117-119) 🟢.
4. `create(...)` строит адаптер нужного типа, также в `try-with-resources` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:120, 137-145) 🟢.
5. `started = new AtomicBoolean()`; `health = healthServer(healthPort, started)` — до запуска подсистем (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:121-122) 🟢.
6. В `try`: `adapter.startWebhook(new InetSocketAddress(webhookPort))` → `startPolling()` → `startReconciliation()` → `started.set(true)` → лог `adapter <SRC> started` → `awaitShutdown()`; `finally` — `health.stop(0)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:123-132) 🟢.
7. После выхода из `awaitShutdown` закрываются адаптер, затем S3-хранилище (обратный порядок `try-with-resources`) (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:117-134) 🟢.

### `healthServer`

`HttpServer.create(port, 0)`; контекст `/health`: `up = started.get() && method == GET`; тело `{"status":"UP"}` либо `{"status":"DOWN"}`, `Content-Type: application/json`, статус `200`/`503`; `server.start()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160) 🟢.

## Fluxos Alternativos

- **Недоступен PostgreSQL или S3 при старте** — исключение выходит из `main` (код JVM 1), `/health` не поднимается, контейнер перезапускается/проваливает healthcheck 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:113, 117-119).
- **Порт занят** — `HttpServer.create`/`startWebhook` бросают исключение до `started=true` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:122, 124) 🟡.
- **Режим `eam-api`** — `EamAdapter.create(config, eamApi, journal, store)`; иначе `EamAdapter.create(config, journal, store)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:146) 🟢.
- **Webhook вне окна replay или с неверной подписью** — решает `WebhookHandler` в `adapter-core` (вне модуля) 🟡.

## Dependências

- `PostgresEventJournal`, `S3RawPayloadStore` — `event-journal` ([`../../event-journal/`](../../event-journal/)).
- `AdapterConfig`, `RetryPolicy`, `SourceAdapter`, `WebhookServer` — `adapter-core` ([`../../adapter-core/`](../../adapter-core/)).
- `EamAdapter`, `ScmAdapter`, `AssetAdapter`, `DeploymapAdapter` — [`../../eam-adapter/`](../../eam-adapter/), [`../../scm-adapter/`](../../scm-adapter/), [`../../asset-adapter/`](../../asset-adapter/), [`../../deploymap-adapter/`](../../deploymap-adapter/).
- Источники polling/webhook: `stub-<src>` ([`../run-source-stub/design.md`](../run-source-stub/design.md)) либо `eam-api-stub` ([`../run-eam-api-stub/design.md`](../run-eam-api-stub/design.md)).
- JDBC `postgresql` (platform/demo-launcher/pom.xml:54-57).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| `/health` создаётся до запуска подсистем и становится `UP` последним шагом | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:121-127 | 🟢 |
| Размер страницы polling зашит (100) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:115 | 🟢 |
| Адаптер не владеет схемой журнала | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:44-47 | 🟢 |
| Один процесс = один источник = один экземпляр | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:44-47; platform/docker/docker-compose.yml:330 | 🟢 |

## Estado Interno

`AtomicBoolean started` (готовность), сам `SourceAdapter` (checkpoint и состояние polling — в PostgreSQL через `PostgresEventJournal`, замок snapshot — в памяти процесса) 🟡.

## Observabilidade

Лог INFO `adapter <SRC> started`; метрик нет. Сторонние логи адаптера — `adapter-core`.

## Riscos e Lacunas

- 🔴 Нет тестов порядка старта и `/health` (Q-05).
- 🟡 Закрытие при SIGTERM не гарантировано (Q-11).
- 🟡 `URI.create` на `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_SOURCE_URL`, `ARCHRAG_EAM_API_URL` может процитировать значение (Q-04).
- 🟡 `/control/snapshot` адаптера без аутентификации (Q-13).
