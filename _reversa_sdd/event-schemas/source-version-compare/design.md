# Use case: source-version-compare, Дизайн

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md). Блок-схема — `_reversa_sdd/flowcharts/event-schemas.md` §3.

## Интерфейс
| Символ | Сигнатура | Поведение |
|---|---|---|
| `SourceVersion` | `record(String value) implements Comparable<SourceVersion>` | валидация в компактном конструкторе |
| `compareTo` | `int compareTo(SourceVersion other)` | `new BigInteger(value).compareTo(new BigInteger(other.value))`; `other == null` → NPE 🟢 |
| `isOlderThan` | `boolean isOlderThan(SourceVersion applied)` | `compareTo(applied) < 0` |

## Решение применения (вне модуля)
Контракт даёт только сравнение. Решение принимает `graph-projector`: нет записи → применить; `incoming < applied` → `IGNORED_OLD_VERSION`; равно → `SAME` (для tombstone — применить, если запись активна); выше → применить (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/VersionDecision.java:18-38`; `_reversa_sdd/state-machines.md:180`) 🟢 (код вне модуля, сверено).

## Зависимости
Потребители: `graph-projector`, `normalizer` (валидация версии источника → `QUARANTINED`), `event-journal` (колонка версии как текст).

## Риски
- 🟡 `equals` (по строке) и `compareTo` (по числу) расходятся для ведущих нулей — не согласованы с контрактом `Comparable` (Q-05).
- 🔴 R-05: `long` в `source-spi` против 40 цифр здесь.
