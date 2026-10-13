# source-conflicts: требования

> Use case модуля [`ingestion/identity-resolution`](../requirements.md). Алиасы `IR`, `MIG`, `IT`, `EP` — как в модульных файлах. Дизайн: [`design.md`](design.md).

## Обзор
Хранение утверждений свойств каждой записи (`property_assertion`) и фиксация расхождений (`source_conflict`): неавторитетный источник (по `AuthorityMatrix`) утверждает значение, отличное от мастера. Граф и canonical-значение не меняются; ручного решения нет 🟢 (`IR/SourceConflicts.java:10-18`, `IR/package-info.java:14-17`). Второй вид — `RELATION` (ADR 0030/0031).

## Требования

| ID | Требование | MoSCoW | Evidence |
|----|-----------|--------|----------|
| UC3-01 | `assertProperties` заменяет все утверждения записи (DELETE+INSERT) и ставит флаг `authoritative = matrix.isAuthoritative(label, property, key.source())` | Must | `IR/PostgresSourceConflicts.java:81-103` 🟢 |
| UC3-02 | Пересчёт `PROPERTY`-конфликтов `gid` под lock'ом `conflict\|<gid>` | Must | `:162-167,210-271` 🟢 |
| UC3-03 | Конфликт: для свойства есть авторитетное утверждение (мастер = первое по ключу записи) и неавторитетное с другим значением (`<>`) | Must | `:41-47` 🟢 |
| UC3-04 | Результат не зависит от порядка прихода утверждений | Must | `IR/SourceConflicts.java:13-15` 🟢 |
| UC3-05 | Закрытие: открытый `PROPERTY`-конфликт, которого нет среди расходящихся, -> `RESOLVED`, `resolved_at = updated_at = now` | Must | `:247-269` 🟢 |
| UC3-06 | Повторное открытие: `status='OPEN'`, `resolved_at=NULL`, `opened_at` сохраняется; `updated_at` не меняется, если строка уже `OPEN` с теми же значениями | Must | `:48-58` 🟢 |
| UC3-07 | `retract(key, gid)`: удалить утверждения записи, закрыть `RELATION`-строки, где она `dissent`, пересчитать | Must | `:121-131` 🟢 |
| UC3-08 | `assertRelation`: `master` пуст или значения равны -> закрыть `RELATION`-строку (`gid`, `property`, `dissent`); иначе upsert `OPEN` вида `RELATION`; возвращает открытые конфликты `gid` обоих видов | Should | `:105-119,176-208` 🟢 |
| UC3-09 | `openConflicts(gid)` — открытые строки, сортировка `property, dissent_source, dissent_type, dissent_id` | Should | `:68-71,133-140,273-297` 🟢 |
| UC3-10 | Пересчёт `PROPERTY` не трогает `RELATION` (`OPEN_KEYS` фильтрует `kind='PROPERTY'`) | Must | `:59-60` 🟢 |
| UC3-11 | Значения источника не логируются, не попадают в `toString` | Must | `IR/SourceConflict.java:77-81` 🟢 |

## Критерии приёмки (`PostgresSourceConflictsIT` на `postgres:16`)

```gherkin
Сценарий 1 (позитивный): расхождение
  Дано EAM (мастер criticality для IT_SYSTEM) утверждает HIGH, CMDB утверждает LOW для того же gid
  Когда выполнен assertProperties CMDB
  Тогда ровно один OPEN-конфликт PROPERTY: property=criticality, dissent=CMDB, master=EAM, значения LOW/HIGH
  # differingNonAuthoritativeValueOpensOneConflict

Сценарий 2 (закрытие и повторное открытие)
  Когда CMDB утверждает HIGH -> конфликт RESOLVED (строка остаётся, resolved_at задан)
  И затем снова LOW -> OPEN, resolved_at = NULL, opened_at прежний
  # equalValueResolvesAndReopeningKeepsOpenedAt

Сценарий 3 (порядок): неавторитетный пришёл раньше мастера
  Тогда первый вызов возвращает пусто, после мастера открыт один конфликт
  # conflictDoesNotDependOnArrivalOrder; masterChangingValueRecomputesPair

Сценарий 4 (негативный): нет мастера
  Дано два неавторитетных источника с разными значениями
  Тогда конфликтов нет
  # onlyNonAuthoritativeAssertionsGiveNoConflict

Сценарий 5 (retract)
  Когда retract dissent -> RESOLVED; когда retract мастера -> RESOLVED
  # retractOfDissentResolvesAndRetractOfMasterResolvesToo

Сценарий 6 (идемпотентность): повтор вызова не меняет ни результат, ни строку (в т. ч. updated_at)
  # repeatedCallLeavesStateUnchanged

Сценарий 7 (изоляция gid): конфликты другого gid не затронуты
  # conflictsOfOtherGidAreNotAffected

Сценарий 8 (RELATION)
  Дано Deployment (DEPLOYMAP) с eamSystemId=5102 в chart, система сервиса 5101
  Когда assertRelation(dissent, gid, "eamSystemId", "5102", Optional.of(EAM/5101), "5101")
  Тогда OPEN, kind=RELATION; повтор идемпотентен; значения равны -> RESOLVED; расхождение вернулось -> OPEN, opened_at прежний
  # relationMismatchOpensIdempotentlyAndResolvesWhenEqual

Сценарий 9 (RELATION: нет мастера): master пуст
  Тогда новая строка не открывается, открытая закрывается
  # relationClosesWhenMasterUnknownAndNeverOpensWithoutIt

Сценарий 10 (независимость видов)
  # retractResolvesRelationAndPropertyRecomputeLeavesItAlone; relationAndPropertyConflictsCoexistOnOneGid

Сценарий 11 (через проектор)
  # platform/integration-tests: SourceConflictIT (событийный путь, ASSERTS.conflicts), 🟡 содержимое не разбиралось в этой спецификации

Сценарий 12 (отказ хранилища): SQL-ошибка
  Тогда rollback и IdentityStoreException("assertProperties|assertRelation|retract failed"); вызывающий -> RETRYING / SOURCE_CONFLICT_FAILED
  # теста на уровне модуля нет 🟡
```

## Трассировка
`IR/SourceConflicts.java`, `IR/PostgresSourceConflicts.java`, `IR/SourceConflict.java`, `MIG/V5__source_conflict.sql`, `MIG/V7__source_conflict_kind.sql`. Тесты: `IT/PostgresSourceConflictsIT`, `IT/SourceConflictIT` (`platform/integration-tests`); в самом модуле тестов нет 🟢.
