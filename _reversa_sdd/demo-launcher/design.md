# platform/demo-launcher: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md). Блок-схемы: [`../flowcharts/demo-launcher.md`](../flowcharts/demo-launcher.md).
> Use case-спецификации: [`run-adapter/`](run-adapter/design.md), [`run-source-stub/`](run-source-stub/design.md), [`run-eam-api-stub/`](run-eam-api-stub/design.md).
> Сквозные части (диспетчер аргументов, конфигурация, завершение, сборка) описаны здесь и в use case не дублируются.

## Интерфейс

Пакет `io.github.unlocker.archrag.demolauncher`. Публичный контракт процесса (аргументы, env, порты) — в [`contracts.md`](contracts.md).

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `DemoLauncher.main` | `(String[] args) -> void` | не возвращается до SIGTERM/SIGINT; `System.exit(2)` при неверных аргументах или конфигурации | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:58-83 🟢 |
| `DemoLauncher.STUB_CONTROL_PORT` | `static final int = 8091` | порт `StubControl`/`EamApiControl` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54 🟢 |
| `DemoLauncher.runAdapter / runStub / runEamApiStub` | private static, принимают запись конфигурации | | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:108-135, 85-95, 97-106 🟢 |
| `DemoLauncher.create` | `(AdapterConfig, EamAdapter.EamApi, PostgresEventJournal, S3RawPayloadStore) -> SourceAdapter` | `switch` по `SourceSystem` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:137-145 🟢 |
| `DemoLauncher.healthServer` | `(int port, AtomicBoolean started) -> HttpServer` | `GET /health` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160 🟢 |
| `DemoLauncher.awaitShutdown` | `() -> void` | блокирует до SIGTERM/SIGINT | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:162-167 🟢 |
| `LauncherConfig.parseSystem` | `(String) -> SourceSystem` | `IllegalArgumentException` для неизвестного/`null` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:98-104 🟢 |
| `LauncherConfig.adapter / stub / eamApiStub` | `(…, Map<String,String> env) -> Adapter / Stub / EamApiStub` | `IllegalArgumentException` с именами переменных | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:107-125, 142-153, 156-163 🟢 |
| `LauncherConfig.Adapter / Stub / EamApiStub` | records с маскирующим `toString` | | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:49-95 🟢 |
| `StubControl` | package-private, `AutoCloseable`; `POST /control/upsert` | см. [`run-source-stub/design.md`](run-source-stub/design.md) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:29-125 🟢 |
| `EamApiControl` | package-private, `AutoCloseable`; `POST /control/upsert|archive` | см. [`run-eam-api-stub/design.md`](run-eam-api-stub/design.md) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:23-112 🟢 |

## Fluxo Principal

1. `main` смотрит аргументы: ровно `["eam-api-stub"]` → `LauncherConfig.eamApiStub(System.getenv())` → `runEamApiStub`; `IllegalArgumentException` → `configuration error: <message>` в stderr и `exit(2)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:59-67) 🟢.
2. Иначе нужны ровно два аргумента, первый `adapter` или `stub`; иначе `usage: DemoLauncher <adapter|stub> <EAM|SCM|CMDB|DEPLOY_MAP> | DemoLauncher eam-api-stub` и `exit(2)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:68-71) 🟢.
3. `LauncherConfig.parseSystem(args[1])` (точное имя элемента `SourceSystem`, регистр важен); затем `adapter(...)` либо `stub(...)`; `IllegalArgumentException` из любого шага → `configuration error`, `exit(2)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:72-82) 🟢.
4. Роль выполняется до SIGTERM/SIGINT ([`run-adapter/design.md`](run-adapter/design.md), [`run-source-stub/design.md`](run-source-stub/design.md), [`run-eam-api-stub/design.md`](run-eam-api-stub/design.md)).
5. `awaitShutdown`: `CountDownLatch(1)`, shutdown hook снимает защёлку; после `await` главный поток выходит из `try-with-resources` роли (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:162-167) 🟢.

## Fluxos Alternativos

- **Исключение времени выполнения, не `IllegalArgumentException`** (`IOException` при старте порта, `IllegalStateException`) — выходит из `main` как необработанное; код выхода JVM 1, `configuration error` не печатается (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:58) 🟡.
- **`IllegalArgumentException` из библиотеки при старте роли** (например, порт вне диапазона для `HttpServer.create`) печатается как `configuration error` с текстом исключения библиотеки (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:79-82) 🟡.
- **`IOException` при старте управляющего входа `eam-api-stub`** превращается в `IllegalStateException("cannot start EAM API stub control")`, а не в код 2 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:107-109) 🟢.
- **SIGTERM** — shutdown hook только снимает защёлку; JVM может завершиться раньше закрытия ресурсов, корректное закрытие адаптера не гарантировано (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:162-167) 🟡.

## Dependências

- Проектные модули: `source-spi`, `adapter-core`, `eam-adapter`, `scm-adapter`, `asset-adapter`, `deploymap-adapter`, `event-schemas`, `event-journal`, `source-stubs`; библиотека `postgresql` (JDBC) (platform/demo-launcher/pom.xml:17-57) 🟢.
- Не зависит от `ingestion/*` (кроме `event-journal` как клиента inbox) и `access/*`; доступа к Neo4j нет (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/package-info.java:1-6) 🟢.
- Сборка: `spring-boot-maven-plugin` с `mainClass = io.github.unlocker.archrag.demolauncher.DemoLauncher` и `repackage` с классификатором `exec`; версия плагина — `${spring-boot.version}` прямо в `build/plugins` (platform/demo-launcher/pom.xml:72-89) 🟢 (Q-09).
- Образ: общий `platform/docker/Dockerfile`, `ENTRYPOINT ["/app/entrypoint.sh"]`, аргументы роли — `command:` сервиса compose; `entrypoint.sh` экспортирует `/run/secrets/<ИМЯ>` как переменные и запускает `java -jar /app/app.jar "$@"` (`platform/docker/Dockerfile:1-14`; `platform/docker/entrypoint.sh:1-10`; platform/docker/docker-compose.yml:6-12) 🟢.
- HTTP-поверхности, которые поднимают другие модули (не специфицируются здесь): `POST /webhook`, `POST /control/snapshot` — `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java:13-26, 54-57`; `GET /changes`, `GET /objects/{type}/{id}` — `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:26-27, 53-54`; `GET /api/**` — `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:26-31, 65` 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Один образ и один `main` на все роли стенда (вместо отдельных приложений) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/package-info.java:1-6; platform/docker/docker-compose.yml:6-12 | 🟢 |
| Plain Java, без Spring: JDK `HttpServer`, `System.Logger`, env | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:51; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/package-info.java:1-6 | 🟢 |
| Конфигурация fail-fast: все недостающие переменные одним исключением, имена без значений | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:107-125, 165-172 | 🟢 |
| Управляющий вход на отдельном порту, не публикуется | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:16-21 | 🟢 |
| `/health` поднимается до старта адаптера и отдаёт 503, пока `started=false` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:120-127 | 🟢 |
| Управляющий вход — по образцу друг друга, но `type` в `EamApiControl` ограничен allowlist'ом, а в `StubControl` нет | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:27, 59-64; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:62 | 🟡 |

## Estado Interno

Процесс держит состояние только в ресурсах роли (HTTP-серверы, пул адаптера, соединения S3/PG). Единственный флаг — `AtomicBoolean started` адаптера (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:121, 127). Данные заглушек — in-memory (`StubSource`, `EamApiStore`), теряются при перезапуске контейнера 🟢.

## Observabilidade

`System.Logger`: INFO `stub <SRC> listens on <port>, control on <port>`, `EAM API stub listens on …`, `adapter <SRC> started`; INFO/WARNING от `StubControl` (`webhook <id> -> adapter status <код>`, `webhook <id> not delivered: <message>`) (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:91-92, 101-102, 128; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:96-103) 🟢. Метрик и трассировок нет.

## Riscos e Lacunas

- 🟡 Управляющий вход без аутентификации на всех интерфейсах (Q-02).
- 🟡 `URI.create(...)` на `S3_ENDPOINT`, `SOURCE_URL`, `EAM_API_URL`, `WEBHOOK_TARGET` может процитировать значение в сообщении (Q-04).
- 🔴 `DemoLauncher.main`, `runAdapter`, `/health` не покрыты тестами (Q-05).
- 🟡 Порт 8091 и размер страницы polling (100) зашиты в код; `ARCHRAG_STUB_PORT` служит и портом `stub`, и портом `eam-api-stub` (Q-06).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 20)] `EamApiControl.TYPES` дублирует литералами типы из `EamApiSeed` (Q-03).
- 🟡 `StubWebhookSender` не закрывается при остановке заглушки (Q-07).

## Зависит от / Связанные спецификации

- Адаптеры и webhook-сервер — [`../adapter-core/`](../adapter-core/), [`../eam-adapter/`](../eam-adapter/), [`../scm-adapter/`](../scm-adapter/), [`../asset-adapter/`](../asset-adapter/), [`../deploymap-adapter/`](../deploymap-adapter/).
- Журнал и raw-хранилище — [`../event-journal/`](../event-journal/).
- Заглушки источников — [`../flowcharts/source-stubs.md`](../flowcharts/source-stubs.md) (отдельной спецификации нет).
- Ingestion (миграции, reconcile через `/control/snapshot`) — [`../ingestion-service/`](../ingestion-service/).
- Compose-стенд — [`../flowcharts/docker.md`](../flowcharts/docker.md).
