# platform/integration-tests: задачи

> Воссоздание тестового модуля с нуля. Use case-задачи — в `*/tasks.md`; здесь сквозные. Origin — файл:строки существующей реализации.

## Pré-requisitos
- [ ] Все проектные модули группы собираются и опубликованы в reactor: `canonical-model`, `event-schemas`, `source-spi`, `adapter-core`, `source-stubs`, четыре адаптера, `event-journal`, `normalizer`, `identity-resolution`, `graph-projector`, `ingestion-service`, `graph-query-core`, `mcp-server`, `admin-console` (`platform/integration-tests/pom.xml:17-103`)
- [ ] Docker доступен для профиля `integration-tests`; BOM корня ставит Testcontainers и AWS SDK перед `spring-boot-dependencies` (CLAUDE.md репозитория)

## Tarefas

- [ ] T-01: `pom.xml` — зависимости `test`-scope без версий, профиль `integration-tests` с `maven-failsafe-plugin` (`integration-test`, `verify`), комментарий про порядок `admin-console` после `mcp-server`
  - Origin: `platform/integration-tests/pom.xml:17-157`
  - Критерий готовности: `./mvnw verify` не запускает ни одного `*IT` и не требует Docker; `./mvnw verify -Pintegration-tests` запускает failsafe (AC-03 модуля)
  - Уверенность: 🟢
- [ ] T-02: маркер модуля и smoke-тест (`IntegrationTestsModule`, `package-info`, `IntegrationTestsSmokeTest`)
  - Origin: `platform/integration-tests/src/main/java/io/github/unlocker/archrag/integrationtests/IntegrationTestsModule.java:3-7`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IntegrationTestsSmokeTest.java:7-13`
  - Критерий готовности: surefire выполняет ровно один тест `moduleMarkerIsOnClasspath`
  - Уверенность: 🟢
- [ ] T-03: образы и среда — `TestImages.NEO4J`, `ContainersSmokeIT` (три smoke-теста и фабрика `s3Container()`)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TestImages.java:6-12`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ContainersSmokeIT.java:26-87`
  - Критерий готовности: AC-01 модуля; образы закреплены точными тегами (PostgreSQL — `postgres:16`, 🟡)
  - Уверенность: 🟢
- [ ] T-04: `McpTestJwt` (RSA-2048, JWKS, `token`/`tokenFor`/`tokenForClient`)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/McpTestJwt.java:31-91`
  - Критерий готовности: claims совпадают с [`contracts.md`](contracts.md) §4; приложение, настроенное на `jwkSetUri()`, принимает токен с верным audience и scope
  - Уверенность: 🟢
- [ ] T-05: вспомогательные `AssetFixtures`, `CanonicalGraph`, `EamApiSupport`, `ScmItSystemMapper`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AssetFixtures.java:30-95`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/CanonicalGraph.java:17-63`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiSupport.java:17-39`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ScmItSystemMapper.java:16-45`
  - Критерий готовности: `AssetFixtures` бросает `IllegalStateException`, если исход проекции не `APPLIED`; `CanonicalGraph.EXCLUDED_KEYS` пуст
  - Уверенность: 🟢
- [ ] T-06: use case «проекция, identity, конфликты» — [`projection-identity-conflicts/tasks.md`](projection-identity-conflicts/tasks.md)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:67-1109` и ещё 9 классов
  - Критерий готовности: AC-01..AC-08 use case
  - Уверенность: 🟢
- [ ] T-07: use case «чтение графа» — [`query-core-read/tasks.md`](query-core-read/tasks.md)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:35-229` и ещё 6 классов
  - Критерий готовности: AC-01..AC-07 use case
  - Уверенность: 🟢
- [ ] T-08: use case «синхронизация и reconcile» — [`sync-reconcile/tasks.md`](sync-reconcile/tasks.md)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:88-543` и ещё 4 класса
  - Критерий готовности: AC-01..AC-07 use case
  - Уверенность: 🟢
- [ ] T-09: use case «приёмка MCP F1–F4» — [`acceptance-mcp-f1-f4/tasks.md`](acceptance-mcp-f1-f4/tasks.md)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:79-428` и F2–F4
  - Критерий готовности: AC-01..AC-07 use case
  - Уверенность: 🟢
- [ ] T-10: use case «консоль, admin REST, безопасность» — [`admin-console-security/tasks.md`](admin-console-security/tasks.md)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:56-477` и ещё 3 класса
  - Критерий готовности: AC-01..AC-08 use case
  - Уверенность: 🟢
- [ ] T-11: job CI `integration-tests` — `./mvnw -B -ntp verify -Pintegration-tests`, артефакт отчётов при падении
  - Origin: `.github/workflows/ci.yml:35-53`
  - Критерий готовности: job запускается без `needs`, параллельно `build`
  - Уверенность: 🟢
- [ ] T-12: 🔴 закрыть пробелы покрытия безопасности и критериев — Q-01 (негативы консоли/crosswalks/reconcile/MCP audience, exp), Q-02 (backup/restore), Q-03 (ловушки 7–8), Q-06 (`stale=true`), Q-18 (расширить `CanonicalGraph`)
  - Origin: [`questions.md`](questions.md)
  - Критерий готовности: решение архитектора по каждому пункту, тесты добавлены либо пробел принят и записан
  - Уверенность: 🔴

## Tarefas de Teste

- [ ] TT-01: 31 класс `*IT` (298 `@Test`) и `IntegrationTestsSmokeTest` (1) — в `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`
  - Origin: таблица классов в [`requirements.md`](requirements.md)
  - Критерий готовности: `./mvnw -B -ntp verify -Pintegration-tests` зелёный на машине с Docker; без Docker заявлять прогон `*IT` нельзя
  - Уверенность: 🟢
- [ ] TT-02: 🟡 заменить тавтологические и слабые утверждения (список — Q-16, Q-17)
  - Origin: [`questions.md`](questions.md) Q-16, Q-17
  - Критерий готовности: тест падает при регрессии, которую он называет
  - Уверенность: 🟡
- [ ] TT-03: 🟡 вынести общий каркас F1–F4 и `awaitTrue` в общий базовый класс или утилиту
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:363-427` и копии (Q-21)
  - Критерий готовности: один источник правды для ожидания и запроса MCP; решение не вводит новую библиотеку
  - Уверенность: 🟡

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08 → T-09 → T-10 → T-11; T-12 после решения архитектора.

## Lacunas Pendentes (🔴)
Q-01, Q-02, Q-03, Q-04, Q-05, Q-06, Q-07, Q-08, Q-09, Q-10, Q-14 — [`questions.md`](questions.md).
