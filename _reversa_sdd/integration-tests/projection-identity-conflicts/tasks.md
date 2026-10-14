# projection-identity-conflicts: задачи

## Pré-requisitos
- [ ] Каркас модуля и образы ([`../tasks.md`](../tasks.md) T-01..T-03); модули `graph-projector`, `event-journal`, `identity-resolution`, `normalizer`, `canonical-model` собираются

## Tarefas

- [ ] T-01: `Neo4jSchemaIT` — идемпотентность `apply`, уникальность `SourceRecord`, дубли `gid`/`code`, FULLTEXT
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:22-90`
  - Критерий готовности: AC-10
  - Уверенность: 🟢
- [ ] T-02: `EventJournalIT` — pending keyset, дедупликация, переходы/DLQ, checkpoint, raw по хешу, читатель журнала
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java:34-300`
  - Критерий готовности: AC-09
  - Уверенность: 🟢
- [ ] T-03: `IdentityMappingIT` и `IdentityCandidateIT`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java:27-192`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityCandidateIT.java:46-275`
  - Критерий готовности: AC-08
  - Уверенность: 🟢
- [ ] T-04: `PostgresSourceConflictsIT` — 12 сценариев `PROPERTY` и `RELATION`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java:27-273`
  - Критерий готовности: AC-03 (PG-часть)
  - Уверенность: 🟢
- [ ] T-05: `GraphProjectorIT` — 51 тест семантики проектора (идемпотентность, версии, неполные записи, authority, темпоральные связи, tombstone, сбои, отложенные связи, миграция темпоральности, `eamSystemOfService`)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:66-1109`
  - Критерий готовности: AC-01, AC-02, AC-04..AC-07
  - Уверенность: 🟢
- [ ] T-06: `SourceConflictIT`, `PlatformInvariantIT`, `DeployedFromIT`, `EamApiProjectionIT`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:59-345`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PlatformInvariantIT.java:46-249`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java:46-371`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiProjectionIT.java:45-271`
  - Критерий готовности: AC-03, AC-04, AC-06, AC-07
  - Уверенность: 🟢
- [ ] T-07: 🔴 тест транзиентного сбоя внутри `executeWrite` проектора с проверкой отсутствия побочных эффектов; тест явного `null` в payload
  - Origin: [`../questions.md`](../questions.md) Q-03, Q-34; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:875-913`
  - Критерий готовности: AC-11
  - Уверенность: 🔴
- [ ] T-08: 🟡 дополнить `PlatformInvariantIT` непокрытыми пунктами ADR 0031 и усилить тавтологичные тесты (`migrationsAreIdempotent`, `repeatedVerIsNoOp…`)
  - Origin: [`../questions.md`](../questions.md) Q-16, Q-27
  - Критерий готовности: каждый тест падает при регрессии, которую называет
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: прогон каждого класса отдельно допустим (`-Dit.test=<Class>`, нет `@Order`); классы без очистки опираются на уникальные ключи
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:113-117`
  - Критерий готовности: зелёный на машине с Docker; без Docker заявлять нельзя
  - Уверенность: 🟢

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06; T-07, T-08 после решения.

## Lacunas Pendentes (🔴)
Q-03, Q-09, Q-11, Q-34 — [`../questions.md`](../questions.md).
