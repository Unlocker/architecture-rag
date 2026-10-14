# relation-command-validation — Задачи

Общий дизайн — [`../design.md`](../design.md). `CM/`, `CT/` — как в [`../requirements.md`](../requirements.md).

## Пререквизиты
- [ ] Реализованы `NodeLabel` (с `isA`), `RelationType`, `Validity`, `SourceKey`, `Invariants`.

## Задачи

- [ ] T-01 — `UpsertRelation`: compact constructor в порядке: NPE-поля → `type.allows` → `validity`/`temporal` → `copyProperties` → identity.
  - Origin: `CM/command/UpsertRelation.java:25-42`
  - Критерий готовности: сообщения IAE: `"<type> does not allow <from> -> <to>"`, `"validity is allowed only for temporal relation <type>"`, `"<type> requires identity properties <set>"`.
  - Уверенность: 🟢
- [ ] T-03 — `DeferRelation(UpsertRelation)` с NPE на `null`.
  - Origin: `CM/command/DeferRelation.java:12-16`
  - Критерий готовности: `DeferRelation(null)` → NPE.
  - Уверенность: 🟢
- [ ] T-04 — `Invariants.copyProperties`.
  - Origin: `CM/Invariants.java:49-77`
  - Критерий готовности: типы значений, конечные `Double`, списки без вложенности, неизменяемая копия.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 — happy path с подтипом (`RUNS_ON → VIRTUAL_MACHINE`): есть (`GraphCommandTest.validityOnlyForTemporal`, `CT/command/GraphCommandTest.java:34`).
- [ ] TT-02 — отказ по метке: есть (`disallowedLabelsRejected`, `:43`).
- [ ] TT-03 — `NaN/±∞` в значении и в списке: есть (`nonFiniteDoublesRejected`, `:49`).
- [ ] TT-04 — неизменяемость и неподдерживаемые/вложенные типы: есть (`propertiesValidatedAndImmutable`, `:59`).
- [ ] TT-05 — `UpsertRelation(DEPLOYED_FROM, ..., Map.of())` → IAE: ОТСУТСТВУЕТ 🔴.
- [ ] TT-06 — `new UpsertRelation(..., properties=null, ...)` → NPE; `DeferRelation(null)` → NPE: ОТСУТСТВУЕТ 🔴.

## Порядок
T-04 → T-01 → T-03 → тесты.

## Открытые 🔴
- Допустим ли `validity == null` у темпоральных связей, и какой `validFrom` ставит проектор? (🟡, определяется в `graph-projector`).
