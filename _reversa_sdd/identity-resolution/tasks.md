# ingestion/identity-resolution: задачи

> `IR` = `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution`, `MIG` = `ingestion/identity-resolution/src/main/resources/db/migration`. Задачи use case: [`gid-mapping-crosswalk/tasks.md`](gid-mapping-crosswalk/tasks.md), [`candidate-matching/tasks.md`](candidate-matching/tasks.md), [`source-conflicts/tasks.md`](source-conflicts/tasks.md).

## Пререквизиты
- [ ] Maven-модуль `ingestion/identity-resolution` под родителем `ingestion`; зависимости `canonical-model` и `org.postgresql:postgresql` (runtime), версии из BOM (`ingestion/identity-resolution/pom.xml:17-27`).
- [ ] PostgreSQL с Flyway из общего `classpath:db/migration`; миграции V1 (`event-journal`) и V3/V6 (`ingestion-service`) присутствуют на classpath запуска (иначе V6 выдаст `GRANT` на несуществующие таблицы).
- [ ] `javax.sql.DataSource` и `AuthorityMatrix` предоставляет вызывающий (`IngestionServiceConfiguration.java:54,59,64`).

## Задачи

- [ ] T-01 Контракты: `Crosswalk`, `Feature`, `FeatureKind`, `IdentityCandidate`, `SourceConflict`, исключения, порты.
  - Origin: `IR/IdentityMapping.java:15-34`, `IR/IdentityCandidates.java:16-32`, `IR/SourceConflicts.java:19-58`, `IR/Crosswalk.java:14-25`, `IR/Feature.java:6-13`, `IR/IdentityCandidate.java:14-22`, `IR/SourceConflict.java:24-82`
  - Критерий готовности: компилируется, `CrosswalkTest` зелёный.
  - Уверенность: 🟢
- [ ] T-02 Миграции V2, V4, V5, V7 точно как в [`contracts.md`](contracts.md) §3 (без CHECK и FK).
  - Origin: `MIG/V2__identity_mapping.sql:3-26`, `MIG/V4__identity_candidate.sql:5-37`, `MIG/V5__source_conflict.sql:6-39`, `MIG/V7__source_conflict_kind.sql:4`
  - Критерий готовности: `flyway migrate` на чистой БД проходит вместе с V1/V3/V6; `V7` добавляет `kind` с default `PROPERTY`.
  - Уверенность: 🟢
- [ ] T-03 Реализовать use case 1 (mapping, crosswalk).
  - Origin: `IR/PostgresIdentityMapping.java:23-162`
  - Критерий готовности: см. [`gid-mapping-crosswalk/tasks.md`](gid-mapping-crosswalk/tasks.md).
  - Уверенность: 🟢
- [ ] T-04 Реализовать use case 2 (признаки, кандидаты).
  - Origin: `IR/FeatureExtractor.java:29-82`, `IR/FeatureNormalizer.java:13-79`, `IR/PostgresIdentityCandidates.java:192-439`
  - Критерий готовности: см. [`candidate-matching/tasks.md`](candidate-matching/tasks.md).
  - Уверенность: 🟢
- [ ] T-05 Реализовать use case 3 (утверждения, конфликты).
  - Origin: `IR/PostgresSourceConflicts.java:33-309`
  - Критерий готовности: см. [`source-conflicts/tasks.md`](source-conflicts/tasks.md).
  - Уверенность: 🟢
- [ ] T-06 `IdentityStoreException` и единый каркас транзакции (commit / rollback / оборачивание `SQLException`, проброс `RuntimeException`).
  - Origin: `IR/IdentityStoreException.java:3-8`, `IR/PostgresIdentityMapping.java:45-60`
  - Критерий готовности: сообщения как в `contracts.md` §2; `CrosswalkConflictException` не оборачивается.
  - Уверенность: 🟢
- [ ] T-07 Маркер модуля и `package-info` (пустой JAR без класса не даёт smoke-теста).
  - Origin: `IR/IdentityResolutionModule.java:4-7`, `IR/package-info.java`, `IdentityResolutionSmokeTest`
  - Критерий готовности: smoke зелёный.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 Unit: `CrosswalkTest` (4), `FeatureNormalizerTest` (7), `FeatureExtractorTest` (8), `IdentityResolutionSmokeTest` (1) — существуют, запускаются `mvn -pl ingestion/identity-resolution verify`.
- [ ] TT-02 IT (в другом модуле, `platform/integration-tests`): `IdentityMappingIT` (9 тестов), `IdentityCandidateIT` (8), `PostgresSourceConflictsIT` (12), `SourceConflictIT`, плюс `GraphProjectorIT`, `PlatformInvariantIT`, `ReconciliationIT`, `F4AcceptanceIT`, `DeployedFromIT`, `EamApiProjectionIT`, `ExplainProvenanceIT` используют порты. Нужен Docker (Testcontainers `postgres:16`) 🟢.
- [ ] TT-03 🟡 Рекомендация, не существующий тест: модульные IT на Postgres-реализации внутри самого модуля (сейчас `mvn -pl <модуль> verify` их не запускает, `_reversa_sdd/tech-debt.md:66` Н-11).

## Порядок
T-01 -> T-02 -> T-06 -> T-03 -> T-04 -> T-05 -> T-07. T-04 использует таблицу `identity_mapping` из T-02 (V2) в `FIND_OTHERS`, поэтому T-03 и T-04 схемно зависимы; код T-05 независим от T-03/T-04.

## Открытые 🔴
Q-01 (`identity_candidate.status`), Q-02 (кандидаты после crosswalk и при пропуске признака), Q-03 (рассогласование `gid` после crosswalk) — [`questions.md`](questions.md).
