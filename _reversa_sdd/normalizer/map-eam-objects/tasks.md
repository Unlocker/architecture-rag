# map-eam-objects — задачи

## Пререквизиты
- [ ] `Payload`, `CommandSink`, `CanonicalMapper` (T-03, T-04 в [`../tasks.md`](../tasks.md)); `ScmMapper.SERVICE` для `SERVICE_DEPENDENCY`.

## Задачи
- [ ] T-EAM-01 Диспетчер `map` по `sourceType` + `UNKNOWN_SOURCE_TYPE`.
  - Origin: `NZ/EamMapper.java:66-118`
  - Критерий готовности: `NormalizerTest#unknownSourceTypeIsQuarantined`.
  - Уверенность: 🟢
- [ ] T-EAM-02 `mapArchObject` для `PLATFORM`/`SOLUTION`/`IT_SYSTEM` + `reference`.
  - Origin: `NZ/EamMapper.java:132-214`
  - Критерий готовности: AC-EAM-01…AC-EAM-05, AC-EAM-08, AC-EAM-09.
  - Уверенность: 🟢
- [ ] T-EAM-03 Таблица критичности и `UNMAPPED_CRITICALITY` (`criticalityRaw`, DEBUG).
  - Origin: `NZ/EamMapper.java:128-130,161-168`
  - Критерий готовности: AC-EAM-04.
  - Уверенность: 🟢
- [ ] T-EAM-04 Плоский `IT_SYSTEM` и `TEAM`.
  - Origin: `NZ/EamMapper.java:71-99`
  - Критерий готовности: AC-EAM-06, AC-EAM-07, AC-15 (warning `DEPENDS_ON_NOT_SUPPORTED`).
  - Уверенность: 🟢
- [ ] T-EAM-05 `SERVICE_DEPENDENCY` через `sink.link`.
  - Origin: `NZ/EamMapper.java:100-115`
  - Критерий готовности: AC-RR-03, AC-RR-04; `NormalizerTest#eamServiceDependencyWithoutOptionalPropertiesHasNoDefaults`, `#eamServiceDependencyTakesValidFromOnlyFromSource`, `#eamServiceDependencyRejectsMissingEndAndUnknownCriticality`, `#eamServiceDependencyIsIdempotent`.
  - Уверенность: 🟢

## Тесты
- [ ] TT-EAM-01 `EamArchObjectMapperTest` — 16 тестов (платформа, решение, система, ссылки, критичность, `ownerTeam`, legacy-flat).
- [ ] TT-EAM-02 `NormalizerTest#eamTeamMapsToTeam`, `#eamItSystemMapsOwner`, `#eamItSystemLevelDependsOnIsNotMappedAndWarns`, `#eamServiceDependency*` (7 тестов).
- [ ] TT-EAM-03 (нет в коде 🟡) Тест `attrs: null` у `IT_SYSTEM` (Q-08) и тест `CRITICAL` в ArchObject (Q-07).

## Порядок
T-EAM-04 (плоские) → T-EAM-02 → T-EAM-03 → T-EAM-05.

## Открытые
🔴 Q-02, Q-03, Q-04, Q-05; 🟡 Q-07, Q-08, Q-13, Q-14 ([`../questions.md`](../questions.md)).
