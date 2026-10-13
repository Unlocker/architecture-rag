# candidate-matching: задачи

Общее — [`../tasks.md`](../tasks.md). Origin: файл:строка (`IR` = `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution`).

- [ ] T-U2-01 `FeatureKind` (веса, strong, `score`), `Feature`, `LabelFamily`.
  - Origin: `IR/FeatureKind.java:14-47`, `IR/Feature.java:6-13`, `IR/LabelFamily.java:10-12`
  - Критерий готовности: `FeatureExtractorTest.ownerIsNotStrongAndScoreIsCappedSum`, `labelFamilyGroupsComputeInstanceSubtypes`.
  - Уверенность: 🟢
- [ ] T-U2-02 `FeatureNormalizer` (hostname, repositoryUrl, name).
  - Origin: `IR/FeatureNormalizer.java:15-78`
  - Критерий готовности: `FeatureNormalizerTest` (7 тестов).
  - Уверенность: 🟢
- [ ] T-U2-03 `FeatureExtractor.labelOf/extract`.
  - Origin: `IR/FeatureExtractor.java:34-81`
  - Критерий готовности: `FeatureExtractorTest` (8 тестов); карта `gids` не меняется.
  - Уверенность: 🟢
- [ ] T-U2-04 `PostgresIdentityCandidates.record` (локи, замена признаков, поиск, upsert).
  - Origin: `IR/PostgresIdentityCandidates.java:239-264,316-427`
  - Критерий готовности: сценарии 1-8 (`IdentityCandidateIT`).
  - Уверенность: 🟢
- [ ] T-U2-05 `forget`, `candidatesOf`.
  - Origin: `IR/PostgresIdentityCandidates.java:266-314`
  - Критерий готовности: сценарий 7.
  - Уверенность: 🟢
- [ ] T-U2-06 Миграция V4 (`identity_feature`, `identity_candidate`, индексы).
  - Origin: `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql:5-37`
  - Критерий готовности: Flyway применяется после V2.
  - Уверенность: 🟢

Тесты: TT-U2-01 сценарии 1-8 (`IdentityCandidateIT`, существует); TT-U2-02 unit-тесты чистых функций (существуют); TT-U2-03 🟡 нет теста на отказ SQL (сценарий 9), на два признака одного вида, на `candidatesOf` порядок `matched`, на нормализацию IPv6/порта в host.

Порядок: T-U2-01 -> T-U2-02 -> T-U2-03 -> T-U2-06 -> T-U2-04 -> T-U2-05.

Открытые 🔴: Q-01 (жизненный цикл `status`), Q-02 (перезапись `matched` при пропуске признака) — [`../questions.md`](../questions.md).
