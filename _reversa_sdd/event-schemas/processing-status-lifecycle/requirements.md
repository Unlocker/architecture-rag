# Use case: processing-status-lifecycle, Требования

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

## Обзор
Допустимые переходы статуса обработки события в журнале. Функция `ProcessingStatus.canTransitionTo(next)` (`ES/ProcessingStatus.java:26-42`) — единственная логика; применение (под блокировкой строки) делает реализация `EventJournal.transition` 🟢.

## Правила
- Переход в себя разрешён всегда (идемпотентный повтор) 🟢 (`:27-29`).
- Конечные: `SUPERSEDED`, `DUPLICATE`, `IGNORED_OLD_VERSION` — выходов нет 🟢 (`:40`). `PROJECTED` ведёт только в `SUPERSEDED` 🟢 (`:39`).
- `QUARANTINED` выходит только в `RETRYING` 🟢 (`:38`).
- Из `RECEIVED`, `VALIDATED`, `NORMALIZED`, `RESOLVED`, `RETRYING` разрешены `QUARANTINED` и `IGNORED_OLD_VERSION`; в `RETRYING` — из `RECEIVED`, `VALIDATED`, `NORMALIZED`, `RESOLVED` 🟢 (`:31-37`).
- `DUPLICATE` достижим только из `RECEIVED` 🟢 (`:31-32`).
- 🔴 `SUPERSEDED` недостижим (R-02); выход из `QUARANTINED` оператором не реализован (R-03).

## Функциональные требования
| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-01 | Прямой путь `RECEIVED→VALIDATED→NORMALIZED→RESOLVED→PROJECTED→SUPERSEDED` разрешён | Must | AC-01 |
| RF-02 | `PROJECTED` не откатывается (`RETRYING`, `RECEIVED` запрещены) | Must | AC-02 |
| RF-03 | Из конечных статусов допустим только переход в себя | Must | AC-03 |
| RF-04 | `QUARANTINED` → только `RETRYING` (и в себя) | Must | AC-04 |
| RF-05 | Сбойные статусы достижимы из активных стадий; `RETRYING → PROJECTED` разрешён | Must | AC-05 |
| RF-06 | Пропуск стадий запрещён (`RECEIVED → PROJECTED` и т. п.) | Should | AC-06 (теста нет 🟡) |

## Критерии приёмки
```gherkin
# AC-01  (ProcessingStatusTest.happyPathMovesForward)
Дано статус RECEIVED
Когда проверяется canTransitionTo по цепочке VALIDATED, NORMALIZED, RESOLVED, PROJECTED, SUPERSEDED
Тогда каждый шаг возвращает true

# AC-02  (ProcessingStatusTest.projectedCannotRollBack)
Дано статус PROJECTED
Когда проверяется переход в RETRYING или RECEIVED
Тогда результат false

# AC-03  (ProcessingStatusTest.terminalStatesOnlyStayPut)
Дано SUPERSEDED, DUPLICATE или IGNORED_OLD_VERSION
Когда проверяется переход в любое значение enum
Тогда true только если next == текущему

# AC-04  (ProcessingStatusTest.quarantineExitsOnlyThroughRetrying)
Дано QUARANTINED
Когда проверяется переход в любое значение
Тогда true только для QUARANTINED и RETRYING

# AC-05  (ProcessingStatusTest.failuresAreReachableFromActiveStages)
Дано RESOLVED
Когда проверяется QUARANTINED; и RETRYING -> PROJECTED
Тогда оба true

# AC-06  (теста нет)
Дано RECEIVED
Когда проверяется переход в PROJECTED
Тогда false
```

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/ProcessingStatus.java` | `ProcessingStatus.canTransitionTo` | 🟢; `ProcessingStatusTest` (5 тестов) |
