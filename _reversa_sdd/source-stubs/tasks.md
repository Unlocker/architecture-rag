# platform/source-stubs: задачи

> Реимплементация модуля с нуля. Use case-задачи — в `*/tasks.md`; здесь сквозные. Origin — файл:строки существующей реализации.

## Pré-requisitos
- [ ] `contracts/source-spi`: `SourceConnector`, `ChangePage`, `SourceChange`, `SourceSystem`, `SourceUnavailableException`, `WebhookEvent`, `WebhookSignature` ([`../source-spi/tasks.md`](../source-spi/tasks.md))
- [ ] Родитель `platform` в корне модулей; модуль объявлен в `dependencyManagement` корня и зависит только от `source-spi` (`platform/source-stubs/pom.xml:18-21`, `pom.xml:89-93`)

## Tarefas

- [ ] T-01: каркас модуля Maven (`artifactId source-stubs`, зависимость `source-spi`, без `src/main/resources`, без внешних библиотек)
  - Origin: `platform/source-stubs/pom.xml:1-23`
  - Критерий готовности: `./mvnw -pl platform/source-stubs -am verify` собирает модуль; в `pom.xml` нет версий и сторонних зависимостей
  - Уверенность: 🟢
- [ ] T-02: `Json` (write/parse) без библиотек
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:14-233`
  - Критерий готовности: `JsonTest.escapesAndNests` зелёный; `parse` возвращает `Long`/`Double`/`Map`/`List` по правилам [`contracts.md`](contracts.md) §6
  - Уверенность: 🟢
- [ ] T-03: журнал `StubSource` и сценарные ручки — см. [`stub-source-journal/tasks.md`](stub-source-journal/tasks.md)
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:29-214`
  - Критерий готовности: AC-01..AC-07 use case `stub-source-journal`
  - Уверенность: 🟢
- [ ] T-04: seed и негативные хелперы — см. [`stub-source-journal/tasks.md`](stub-source-journal/tasks.md) T-05
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:16-221`
  - Критерий готовности: AC-08, AC-09 use case `stub-source-journal`
  - Уверенность: 🟢
- [ ] T-05: `StubSourceServer` — см. [`stub-source-http/tasks.md`](stub-source-http/tasks.md)
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:31-187`
  - Критерий готовности: AC-01..AC-05 use case `stub-source-http`
  - Уверенность: 🟢
- [ ] T-06: заглушка EAM API — см. [`eam-api-stub/tasks.md`](eam-api-stub/tasks.md)
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:36-240`
  - Критерий готовности: AC-01..AC-08 use case `eam-api-stub`
  - Уверенность: 🟢
- [ ] T-07: `StubWebhookSender` — см. [`signed-webhook/tasks.md`](signed-webhook/tasks.md)
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:21-96`
  - Критерий готовности: AC-01..AC-04 use case `signed-webhook`
  - Уверенность: 🟢
- [ ] T-08: подключение модуля потребителями в `test`-scope (адаптеры, нормализатор, `platform/integration-tests`) и в основной scope (`platform/demo-launcher`)
  - Origin: `adapters/eam-adapter/pom.xml:31-35`, `adapters/scm-adapter/pom.xml:31-35`, `adapters/asset-adapter/pom.xml:31-35`, `adapters/deploymap-adapter/pom.xml:37-41`, `adapters/adapter-core/pom.xml:27-31`, `ingestion/normalizer/pom.xml:30-34`, `platform/integration-tests/pom.xml:38-42`, `platform/demo-launcher/pom.xml:50-53`
  - Критерий готовности: `grep` по `pom.xml` находит ровно эти потребители; ни один модуль `access/*` модуль не использует
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `StubSourceTest` (17), `StubSourceServerTest` (8), `EamApiStubTest` (18), `StubWebhookSenderTest` (4), `JsonTest` (1) — 48 `@Test`, без Docker
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java:27-252`, `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceServerTest.java:54-120`, `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/EamApiStubTest.java:52-203`, `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java:62-90`, `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/JsonTest.java:12-20`
  - Критерий готовности: surefire зелёный без Docker
  - Уверенность: 🟢
- [ ] TT-02: 🔴 тесты на битые курсоры `s…`/`i…`, `sort=","`, `sort=id, -mtime`, snapshot с `PARTIAL`, `fields` с пробелами, `/api/types/{id}/` с несуществующим id, `EamApiTypeMeta` для `itproducts`, параллельные вызовы `StubSource`
  - Origin: пробелы Y1.7 в [`../code-analysis.md`](../code-analysis.md); [`questions.md`](questions.md) Q-01, Q-02, Q-07
  - Критерий готовности: каждый случай имеет тест, фиксирующий выбранное решение (400 либо иное)
  - Уверенность: 🔴

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08; TT-01 пишется вместе с каждой задачей.

## Lacunas Pendentes (🔴)
Q-01, Q-02, Q-03, Q-04, Q-05 — [`questions.md`](questions.md).
