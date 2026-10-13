# run-adapter: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §2.1, §3.1.

## Visão Geral

Запуск `DemoLauncher adapter <EAM|SCM|CMDB|DEPLOY_MAP>`: процесс собирает адаптер одного источника поверх PostgreSQL inbox (`PostgresEventJournal`) и S3-хранилища raw payload, поднимает webhook-сервер (`POST /webhook`, `POST /control/snapshot`), polling и reconciliation и открывает `GET /health`, который отвечает `200` только после старта всех трёх подсистем. Для источника EAM по `ARCHRAG_EAM_MODE=eam-api` адаптер дополнительно читает `eam-api` (заглушку формата реального API), а `TEAM` и `SERVICE_DEPENDENCY` по-прежнему берёт из старой заглушки.

## Regras de Negócio

- Миграции журнала адаптер не накатывает (их делает `ingestion-service`); поэтому адаптер стартует после `ingestion` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:44-47, 108-113; platform/docker/docker-compose.yml:32-36).
- Параметры адаптера: источник из аргумента, `sourceUrl`, секрет webhook, окно replay `AdapterConfig.DEFAULT_REPLAY_WINDOW`, размер страницы polling `100`, интервал polling (30 с по умолчанию), интервал reconciliation (6 ч по умолчанию), `RetryPolicy.defaults()` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:114-116; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:119-124).
- Бакет S3 создаётся при старте, если его нет (`ensureBucket`) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:117-119).
- Тип адаптера выбирается по `SourceSystem`: `EAM` → `EamAdapter.create` (с `eamApi` либо без), `SCM` → `ScmAdapter`, `CMDB` → `AssetAdapter`, `DEPLOY_MAP` → `DeploymapAdapter` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:137-145).
- Порядок старта: `/health` создаётся первым (отвечает `503 DOWN`), затем `startWebhook` → `startPolling` → `startReconciliation`, затем `started=true` (`200 UP`) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:121-127, 147-160).
- `GET /health`: `200 {"status":"UP"}` только при `started` и методе `GET`; иначе `503 {"status":"DOWN"}` (в том числе на `POST`) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160).
- Webhook-сервер слушает порт `ARCHRAG_WEBHOOK_PORT` (8080), health — `ARCHRAG_HEALTH_PORT` (8090); оба на всех интерфейсах контейнера 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:124, 147-148; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:123).
- В режиме `eam-api` (`ARCHRAG_EAM_MODE=eam-api`) обязательны `ARCHRAG_EAM_API_URL` и `ARCHRAG_EAM_API_TOKEN`; по умолчанию (`legacy`) работает только старая заглушка 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:128-139).
- Для источников, кроме EAM, `ARCHRAG_EAM_MODE` игнорируется 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:117-118; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:152-158).
- Ровно один экземпляр на источник: замок snapshot внутри процесса; в compose `deploy.replicas: 1` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:44-47; platform/docker/docker-compose.yml:330).
- Остановка: `finally` останавливает health-сервер; адаптер и S3-клиент закрываются `try-with-resources` при выходе из `awaitShutdown`; гарантии закрытия при SIGTERM нет 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:117-134, 162-167).
- Секреты (пароль PG, ключи S3, секрет webhook, токен EAM API) не попадают в `toString` и в сообщения исключений 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:66-70; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:76-80).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Сборка `Adapter` из env с умолчаниями и валидацией | Must | AC-01, AC-03 |
| RF-02 | Старт PG-журнала, S3 (`ensureBucket`), адаптера нужного типа | Must | AC-02 |
| RF-03 | Запуск webhook, polling, reconciliation в строгом порядке | Must | AC-02 |
| RF-04 | `GET /health`: `503` до готовности, `200` после | Must | AC-02 |
| RF-05 | Режим EAM: `legacy` или `eam-api` | Must | AC-04 |
| RF-06 | Остановка по SIGTERM/SIGINT | Should | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Disponibilidade | Healthcheck compose `curl -fs http://127.0.0.1:8090/health`, до 20 попыток по 5 с, start_period 10 с | platform/docker/docker-compose.yml:331-336 | 🟢 |
| Escalabilidade | Один экземпляр адаптера на источник | platform/docker/docker-compose.yml:330 | 🟢 |
| Безопасность | Секрет webhook, пароль PG, ключи S3 — Docker secrets; в `toString` маскируются | platform/docker/docker-compose.yml:22-26; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:66-70 | 🟢 |
| Безопасность | Webhook проверяет подпись; `/control/snapshot` без аутентификации (граница сети) | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java:13-26` | 🟡 |
| Observabilidade | Лог `adapter <SRC> started` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:128 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (LauncherConfigTest.adapterDefaults; adapterOverrides)
Дано полная конфигурация адаптера EAM без необязательных переменных
Когда LauncherConfig.adapter(EAM, env)
Тогда webhookPort = 8080, healthPort = 8090, pollInterval = 30 с, reconcileInterval = 6 ч, sourceUrl = ARCHRAG_SOURCE_URL
Дано ARCHRAG_WEBHOOK_PORT=9000 и ARCHRAG_POLL_INTERVAL_SECONDS=5
Тогда webhookPort = 9000, pollInterval = 5 с

# AC-02 (проверяется смоуком стенда; тестов модуля нет, см. Q-05)
Дано корректная конфигурация, PostgreSQL с миграциями и S3
Когда запущен DemoLauncher adapter EAM
Тогда пока webhook/polling/reconciliation не стартовали, GET :8090/health -> 503 {"status":"DOWN"}
      после старта GET :8090/health -> 200 {"status":"UP"}, POST :8090/health -> 503

# AC-03 (LauncherConfigTest.missingRequiredFailsFastNamingVariablesOnly; invalidPortAndIntervalAreRejected; toStringHidesSecrets)
Дано нет ARCHRAG_PG_PASSWORD и ARCHRAG_WEBHOOK_SECRET из пробелов
Когда разбирается конфигурация
Тогда IAE называет обе переменные и не содержит значений секретов; процесс завершается кодом 2
Дано ARCHRAG_WEBHOOK_PORT=70000, ARCHRAG_POLL_INTERVAL_SECONDS=0 либо ARCHRAG_HEALTH_PORT=abc
Тогда IAE называет переменную, значение не цитируется

# AC-04 (LauncherConfigTest.eamAdapterDefaultsToLegacyModeWithoutEamApi; eamApiModeRequiresUrlAndTokenAndHidesToken; unknownEamModeIsRejectedAndOtherSourcesIgnoreIt)
Дано ARCHRAG_EAM_MODE пусто или legacy
Тогда eamApi = null (читается только старая заглушка)
Дано ARCHRAG_EAM_MODE=eam-api с URL и токеном
Тогда eamApi = EamApi(URL, токен); toString токен не содержит
Дано ARCHRAG_EAM_MODE=eam-api без токена
Тогда IAE называет ARCHRAG_EAM_API_TOKEN
Дано ARCHRAG_EAM_MODE=bogus
Тогда IAE для EAM; для SCM режим игнорируется

# AC-05 (смоук стенда; тестов модуля нет)
Дано работающий адаптер
Когда контейнеру приходит SIGTERM
Тогда главный поток выходит из awaitShutdown, health-сервер останавливается; закрытие адаптера гарантировано не полностью (🟡 Q-11)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Адаптеры — путь данных в inbox на стенде |
| RF-06 | Should | Остановка контейнера |
| Настройка размера страницы и replay-окна через env | Won't | Константы кода (🟡 Q-06) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java | `runAdapter`, `create`, `healthServer`, `awaitShutdown` | 🟢 |
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java | `Adapter`, `adapter`, `eamApi` | 🟢 |
