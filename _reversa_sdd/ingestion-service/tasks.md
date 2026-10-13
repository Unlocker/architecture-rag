# ingestion/ingestion-service: задачи

> Задачи use case: [`dispatch-journal/`](dispatch-journal/tasks.md), [`replay-rebuild/`](replay-rebuild/tasks.md), [`reconcile-source/`](reconcile-source/tasks.md), [`load-crosswalk/`](load-crosswalk/tasks.md). Здесь — сквозные части.

## Pré-requisitos
- [ ] Модули `canonical-model`, `event-schemas`, `source-spi`, `event-journal`, `normalizer`, `identity-resolution`, `graph-projector` собираются (`ingestion/ingestion-service/pom.xml:17-41, 77-80`)
- [ ] PostgreSQL, Neo4j (writer), S3-совместимое хранилище, OIDC issuer доступны
- [ ] Переменные окружения из [`contracts.md`](contracts.md) §4 заданы (секреты — из env/Docker secrets)

## Tarefas

- [ ] T-01: Миграция `V3__admin_audit.sql` (таблица и два индекса)
  - Origin: `ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql:2-16`
  - Критерий готовности: Flyway на classpath трёх модулей даёт V1..V7 без конфликта версий
  - Уверенность: 🟢
- [ ] T-02: Миграция `V6__console_readonly_role.sql` (роль без пароля, `USAGE`, `SELECT` на шесть таблиц)
  - Origin: `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-16`
  - Критерий готовности: роль читает шесть таблиц и не читает `identity_mapping`, `identity_feature`, `property_assertion` (SQLSTATE 42501) — проверяют `AdminConsoleIT`/`SyncConsoleIT` 🟡
  - Уверенность: 🟢
- [ ] T-03: `AdminLock` (pg advisory lock `0x41524348_41444D31L`, отдельное соединение, без ожидания)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java:19-86`
  - Критерий готовности: второй `acquire` даёт `AdminBusyException`; `Lease.close` снимает замок и закрывает соединение
  - Уверенность: 🟢 (юнит-теста нет; `AdminEndpointIT.concurrentAdminOperationIsRejectedWith409`)
- [ ] T-04: `AdminAudit` (`start`/`finish`, усечение `error` до 200)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java:22-68`
  - Критерий готовности: AC-08 из `requirements.md`
  - Уверенность: 🟢
- [ ] T-05: `AdminOperations.run`/`locked`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:38-76`
  - Критерий готовности: `AdminOperationsTest` (3 теста)
  - Уверенность: 🟢
- [ ] T-06: Безопасность: `SecurityConfiguration`, `AuthorizedPartyValidator`, `AdminProperties`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:24-46`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java:14-32`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminProperties.java:13-20`
  - Критерий готовности: AC-05..AC-07
  - Уверенность: 🟢
- [ ] T-07: `AdminController`: маршруты, валидации, обработчики 400/409/502
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:27-156`
  - Критерий готовности: `AdminControllerTest` (11 тестов)
  - Уверенность: 🟢
- [ ] T-08: `IngestionMetrics` (предрегистрация серий, gauges по расписанию, `sourceTag`)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:39-168`
  - Критерий готовности: `IngestionMetricsTest` (11 тестов), `IngestionMetricsIT`
  - Уверенность: 🟢
- [ ] T-09: Свойства: `Neo4jProperties`, `S3Properties` (без секретов в `toString`), `application.yml`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/Neo4jProperties.java:6-12`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/S3Properties.java:6-12`, `ingestion/ingestion-service/src/main/resources/application.yml:1-65`
  - Критерий готовности: сервис не стартует без обязательных переменных (`ARCHRAG_PG_URL`, `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_ADMIN_RESOURCE_URI`, `ARCHRAG_NEO4J_*`, `ARCHRAG_S3_*`)
  - Уверенность: 🟢
- [ ] T-10: `IngestionServiceConfiguration` (порядок: миграции → identity-бины → Neo4j схема + темпоральная миграция → S3 `ensureBucket` → проектор → reconciler → процессор → метрики → диспетчер)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:40-135`
  - Критерий готовности: контекст поднимается в `AdminEndpointIT`/`JournalDispatcherIT`; Flyway Boot не подключён
  - Уверенность: 🟢
- [ ] T-11: `IngestionServiceApplication` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`) и repackage с классификатором `exec`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceApplication.java:8-14`; `ingestion/ingestion-service/pom.xml:98-116`
  - Критерий готовности: основной артефакт — обычный jar (на него зависит `platform/integration-tests`), исполняемый — `*-exec.jar`
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `AdminControllerTest` — 401/403/400/409/404/502, `otherPathsAreDenied`
- [ ] TT-02: `AuthorizedPartyValidatorTest` — разрешённый клиент, чужой, нет claim, пустой allow-list
- [ ] TT-03: `AdminOperationsTest` — успех + сбой аудита после, FAILED с именем класса, сбой `STARTED`
- [ ] TT-04: `IngestionMetricsTest`, `IngestionMetricsIT`
- [ ] TT-05: `AdminEndpointIT` — токены (`tokenForForeignAudienceIsUnauthorized`, `tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope`, `scopeIsEnforced`), `concurrentAdminOperationIsRejectedWith409`
- [ ] TT-06: 🔴 нет теста конкуренции диспетчера и админской операции (`AdminEndpointIT` отключает диспетчер, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:101`) — Q-06
- [ ] TT-07: 🟢 [R.8: ответ владельца, вопрос 9] нет теста совпадения литералов типов событий (`snapshot-complete`, `deleted`) между адаптером и ingestion — Q-05

## Tarefas de Migração de Dados

- [ ] TM-01: Каталог `db/migration` общий для трёх модулей: `V3` и `V6` принадлежат этому модулю; новые версии нумеровать сквозно (`V8+`), не дублируя номера других модулей (spec-impact-matrix §2 № 14)

## Ordem Sugerida
1. T-01, T-02, T-09 → 2. T-03, T-04, T-05 → 3. T-06, T-07 → 4. use case: replay-rebuild, load-crosswalk, reconcile-source → 5. T-08, dispatch-journal → 6. T-10, T-11 (сборка).

## Lacunas Pendentes (🔴)
См. [`questions.md`](questions.md): Q-01..Q-10.
