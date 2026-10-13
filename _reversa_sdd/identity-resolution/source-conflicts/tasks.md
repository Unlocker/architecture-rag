# source-conflicts: задачи

Общее — [`../tasks.md`](../tasks.md). `IR` = `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution`.

- [ ] T-U3-01 Модель `SourceConflict` (records, `Kind`, `Status`, `toString` без значений).
  - Origin: `IR/SourceConflict.java:24-82`
  - Критерий готовности: компиляция, значения не в `toString`.
  - Уверенность: 🟢
- [ ] T-U3-02 Миграции V5 и V7.
  - Origin: `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql:6-39`, `V7__source_conflict_kind.sql:4`
  - Критерий готовности: Flyway после V2/V4; старые строки получают `kind='PROPERTY'`.
  - Уверенность: 🟢
- [ ] T-U3-03 `assertProperties` + `recompute` (SQL `DIVERGING`, `UPSERT_CONFLICT`, `OPEN_KEYS`, `RESOLVE`).
  - Origin: `IR/PostgresSourceConflicts.java:36-71,81-103,210-271`
  - Критерий готовности: сценарии 1-7 `PostgresSourceConflictsIT`.
  - Уверенность: 🟢
- [ ] T-U3-04 `assertRelation`, `resolveRelation`, `upsertRelation`.
  - Origin: `IR/PostgresSourceConflicts.java:105-119,176-208`
  - Критерий готовности: сценарии 8-10.
  - Уверенность: 🟢
- [ ] T-U3-05 `retract`, `openConflicts`, `selectOpen`.
  - Origin: `IR/PostgresSourceConflicts.java:121-140,273-297`
  - Критерий готовности: сценарии 5, 7, 10.
  - Уверенность: 🟢

Тесты: TT-U3-01 сценарии 1-10 (`PostgresSourceConflictsIT`, существует); TT-U3-02 событийный путь (`SourceConflictIT`, существует); TT-U3-03 🟡 нет теста: отказ SQL, несколько авторитетных источников одного свойства, retract мастера `RELATION`, смена матрицы.

Порядок: T-U3-01, T-U3-02 -> T-U3-03 -> T-U3-05 -> T-U3-04.

Открытые 🔴: нет собственных (см. 🟡 в `design.md`); общие — [`../questions.md`](../questions.md).
