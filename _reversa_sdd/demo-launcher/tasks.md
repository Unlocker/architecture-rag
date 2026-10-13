# platform/demo-launcher: задачи

> Задачи use case: [`run-adapter/`](run-adapter/tasks.md), [`run-source-stub/`](run-source-stub/tasks.md), [`run-eam-api-stub/`](run-eam-api-stub/tasks.md). Здесь — сквозные части (сборка, диспетчер аргументов, конфигурация, завершение, compose).

## Pré-requisitos
- [ ] Модули `source-spi`, `adapter-core`, `eam-adapter`, `scm-adapter`, `asset-adapter`, `deploymap-adapter`, `event-schemas`, `event-journal`, `source-stubs` собираются (platform/demo-launcher/pom.xml:17-53)
- [ ] Для роли `adapter`: PostgreSQL с применёнными миграциями журнала (делает `ingestion-service`), S3-совместимое хранилище, переменные из [`contracts.md`](contracts.md) §2.1 (секреты — из Docker secrets)

## Tarefas

- [ ] T-01: Сборка: `spring-boot-maven-plugin` с `mainClass = io.github.unlocker.archrag.demolauncher.DemoLauncher`, `repackage` с классификатором `exec`; зависимости модуля
  - Origin: platform/demo-launcher/pom.xml:17-89
  - Критерий готовности: основной артефакт — обычный jar, исполняемый — `demo-launcher-*-exec.jar` (его копирует `platform/docker/Dockerfile` через `ARG JAR`)
  - Уверенность: 🟢
- [ ] T-02: `LauncherConfig`: константы имён env, записи `Adapter`, `Stub`, `EamApiStub`, разбор `adapter`, `stub`, `eamApiStub`, `parseSystem`, `required`, `port`, `seconds`
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:17-204
  - Критерий готовности: `LauncherConfigTest` (14 тестов) зелёный; секреты не в `toString` и не в сообщениях ошибок
  - Уверенность: 🟢
- [ ] T-03: `DemoLauncher.main`: диспетчер аргументов, коды выхода, usage
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54-83
  - Критерий готовности: AC-01, AC-02 из `requirements.md`; 🔴 юнит-тестов нет (Q-05)
  - Уверенность: 🟢 (поведение), 🔴 (покрытие)
- [ ] T-04: `awaitShutdown`: защёлка + shutdown hook
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:162-167
  - Критерий готовности: процесс держится до SIGTERM/SIGINT, затем выходит из `main`
  - Уверенность: 🟢
- [ ] T-05: `package-info.java` с описанием ролей
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/package-info.java:1-6
  - Критерий готовности: Javadoc пакета называет роли и ограничения («plain Java», «без Neo4j-доступа»)
  - Уверенность: 🟢
- [ ] T-06: Compose-привязка: общий YAML-якорь `x-demo-launcher`, восемь сервисов (`adapter-*`, `stub-*`), `eam-api-stub`, secrets, networks, healthchecks
  - Origin: platform/docker/docker-compose.yml:6-36, 311-445
  - Критерий готовности: `docker compose up -d --wait` поднимает все роли; `adapter-*` стартуют после `ingestion` healthy; `deploy.replicas: 1` на адаптерах
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `LauncherConfigTest` — умолчания, переопределения, перечисление имён без значений, невалидные порт/интервал, `toString`, неизвестный источник, режимы EAM (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:28-158)
- [ ] TT-02: `StubControlTest` — upsert, 400/405, лимит тела, подписанный webhook, `notify:false`, недоступный получатель (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:55-183)
- [ ] TT-03: `EamApiControlTest` — upsert/`ver`, archive, плохие запросы, `/api/**` read-only (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:47-80)
- [ ] TT-04: 🔴 нет теста `DemoLauncher.main` (маршрутизация, `exit(2)`), `runAdapter` (порядок старта) и `GET /health` (Q-05)
- [ ] TT-05: 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 20)] нет теста на согласованность `EamApiControl.TYPES` с `EamApiSeed` (Q-03)
- [ ] TT-06: интеграционных тестов (`*IT`) в модуле нет; запуск ролей проверяет смоук стенда `platform/docker/smoke.sh` (вне модуля)

## Ordem Sugerida
1. T-01, T-02 → 2. T-03, T-04 → 3. use case: run-source-stub, run-eam-api-stub, run-adapter → 4. T-05, T-06.

## Lacunas Pendentes (🔴 и 🟡)
См. [`questions.md`](questions.md): Q-01..Q-18.
