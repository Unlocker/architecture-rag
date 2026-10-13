# Use case: source-version-compare, Требования

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

## Обзор
Версия объекта в источнике: хранение строкой, сравнение числом. Нужна для решения «применять / игнорировать устаревшее» (`IGNORED_OLD_VERSION`), которое принимает `graph-projector` (`VersionDecision`), не контракт 🟢 (`ES/SourceVersion.java:3-9,18-26`).

## Правила
- Допустимое значение: `^[0-9]{1,40}$` (метод `matches` — полное совпадение); `null`, пустая строка, знак, пробелы, буквы, 41+ цифр → `IllegalArgumentException("sourceVersion must be a non-negative decimal number")` 🟢 (`:12-16`).
- `compareTo` — через `BigInteger`: `"99" < "184"`; ведущие нули допустимы и числа не различают (`"007"` ≡ `"7"` по `compareTo`, но `equals` — по строке) 🟡.
- `isOlderThan(applied)` — `compareTo(applied) < 0` (строго) 🟢 (`:24-26`).
- `AssetEventData.sourceVersion` обязателен (`null` → IAE) 🟢 (`ES/AssetEventData.java:19-21`).
- Нечисловая версия отклоняется на валидации, а не сравнивается лексикографически 🟢 (`:7-8`).
- 🔴 R-05: `SourceChange.sourceVersion` — `long`, значения за пределами `Long.MAX_VALUE` до источника-адаптера не доходят.

## Функциональные требования
| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-01 | Числовое, не лексикографическое сравнение | Must | AC-01 |
| RF-02 | Нечисловая/`null` версия отвергается | Must | AC-02 |
| RF-03 | Равные версии не «старше» | Must | AC-03 |
| RF-04 | Граница длины: 40 цифр принимается, 41 отвергается | Should | AC-04 (теста нет 🟡) |

## Критерии приёмки
```gherkin
# AC-01  (SourceVersionTest.comparesNumericallyNotLexically)
Дано версии "99" и "184"
Когда "99".isOlderThan("184") и "184".isOlderThan("99")
Тогда true и false

# AC-02  (SourceVersionTest.rejectsNonNumeric)
Дано значение "v2" или null
Когда создаётся SourceVersion
Тогда IllegalArgumentException

# AC-03  (SourceVersionTest.comparesNumericallyNotLexically)
Дано "184" и "184"
Когда "184".isOlderThan("184")
Тогда false

# AC-04  (теста нет)
Дано строка из 40 цифр, затем из 41 цифры
Когда создаётся SourceVersion
Тогда первая принимается, вторая — IllegalArgumentException
```

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SourceVersion.java` | `SourceVersion` | 🟢; `SourceVersionTest` |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/AssetEventData.java` | `AssetEventData` | 🟢; `AssetEventDataTest`, `CanonicalEventTest` |
