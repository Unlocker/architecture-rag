# gid-mapping-crosswalk: задачи

Общие пререквизиты и порядок — [`../tasks.md`](../tasks.md). Origin: файл:строка.

- [ ] T-U1-01 Реализовать `PostgresIdentityMapping.getOrCreate`/`select`/`bindKey`.
  - Origin: `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:124-161`
  - Критерий готовности: сценарии 1-3 из `requirements.md` (`IdentityMappingIT`) зелёные.
  - Уверенность: 🟢
- [ ] T-U1-02 `resolve`, `find`, `keysOf` с обёрткой `IdentityStoreException`.
  - Origin: `PostgresIdentityMapping.java:36-78`
  - Критерий готовности: сообщения `resolve failed`/`find failed`/`keysOf failed`, rollback при ошибке.
  - Уверенность: 🟢
- [ ] T-U1-03 `approve`: нормализация пары, `link`, `insertCrosswalk`, конфликт.
  - Origin: `PostgresIdentityMapping.java:80-145`
  - Критерий готовности: сценарии 4-6, 8.
  - Уверенность: 🟢
- [ ] T-U1-04 `Crosswalk` с инвариантами, `SourceKeys.compare`.
  - Origin: `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/Crosswalk.java:14-25`, `SourceKeys.java:10-16`
  - Критерий готовности: `CrosswalkTest` (4 теста).
  - Уверенность: 🟢

Тесты: TT-U1-01 сценарии 1-6, 8 как `*IT` на PostgreSQL (существуют в `platform/integration-tests`: `IdentityMappingIT`); TT-U1-02 `CrosswalkTest` (существует); TT-U1-03 🟡 нет теста на `keysOf` порядок и на `IllegalArgumentException` при неизвестном `source` в БД.

Порядок: T-U1-04 -> T-U1-01 -> T-U1-02 -> T-U1-03.

Открытые 🔴: нет (см. 🟡 в `design.md`).
