# budget-limits: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Исполнение: [`../execute-template/requirements.md`](../execute-template/requirements.md).

## Visão Geral

Бюджет ограничивает запрос по глубине, числу строк/путей, времени и размеру ответа. Запрошенный вызывающим бюджет режется до потолков `QueryLimits`, которые задаёт конфигурация приложения; результат усекается с флагом `truncated`.

## Regras de Negócio

- `ResultBudget` и `QueryLimits` требуют положительных значений; `timeout` не `null`, не ноль, не отрицательный 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:20-27`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:17-20`).
- `clampTo` берёт `min` по каждому полю; таймаут — меньший из двух 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:30-37`).
- Бюджет `null` в `execute` заменяется потолками целиком 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:67`).
- `maxDepth` бюджета подставляется в `{maxDepth}` как число; диапазон `1..limits.maxDepth` проверяет `render` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:68`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:133-138`).
- Потолок строк: `NODES` → `maxNodes`, `PATHS` → `maxPaths` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java:11-13`).
- Исполнитель просит у Neo4j `maxRows + 1` строк (`limit`), чтобы отличить «ровно maxRows» от «больше» 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:69-72`).
- `BudgetedCollector` не читает из итератора больше `maxRows + 1` элементов; `(maxRows+1)`-я строка отбрасывается, `truncated=true` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:45-50`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java:49`).
- Накопленный сериализованный размер строк (JSON, Jackson) сравнивается с `maxBytes`; строка, после которой порог превышен, не добавляется, сбор прекращается, `truncated=true`; единственная строка больше порога даёт пустой усечённый результат 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:51-54`; тесты `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java:58, 66`).
- Ровно `maxRows` строк — не усечение 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:57`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java:42`).
- `BudgetedCollector(maxRows<1 или maxBytes<1)` → `IllegalArgumentException` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:32-38`).
- `truncated` не отражает обрезку по глубине обхода 🔴 (Q-03).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Урезать запрошенный бюджет до потолков | Must | AC-01 |
| RF-02 | Усечь результат по числу строк | Must | AC-02 |
| RF-03 | Усечь результат по размеру ответа | Must | AC-03 |
| RF-04 | Отвергнуть непозитивный бюджет | Must | AC-05 |
| RF-05 | Подставить глубину в шаблон только в диапазоне `1..ceiling` | Must | AC-04 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Производительность | Память ответа ограничена `maxBytes` плюс одной строкой | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:51-54` | 🟢 |
| Производительность | Чтение из курсора Neo4j прекращается на `maxRows+1` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:45` | 🟢 |
| Точность | Размер считается по JSON-сериализации строки, а не по байтам протокола Bolt | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:60-66` | 🟡 |
| Безопасность | Потолки не обходятся ни запрошенным бюджетом, ни `null` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:66-67` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (ResultBudgetTest.requestedBudgetIsClampedToLimits, smallerBudgetIsKept; GraphQueryCoreIT.requestedBudgetIsClampedToConfiguredCeiling)
Дано потолки maxNodes=3
Когда запрошен бюджет maxNodes=1000 на 20 узлов
Тогда вернулось 3 строки и truncated=true
Дано бюджет меньше потолков (глубина 2, 5 строк, 100 мс, 10 байт)
Когда clampTo(потолки)
Тогда бюджет не изменился

# AC-02 (BudgetedCollectorTest.moreRowsThanLimitAreCutAndNeverReadBeyondMaxRowsPlusOne, exactlyMaxRowsIsNotTruncated; GraphQueryCoreIT.rowBudgetTruncates)
Дано maxRows=5 и итератор из 1000 строк
Когда collect
Тогда 5 строк, truncated=true, из итератора прочитано 6
Дано ровно 5 строк
Когда collect
Тогда 5 строк, truncated=false

# AC-03 (BudgetedCollectorTest.byteThresholdTruncatesAndDropsOffendingRow, rowLargerThanByteBudgetGivesEmptyTruncatedResult; GraphQueryCoreIT.byteBudgetTruncates)
Дано maxBytes=30 и строки по 12 байт JSON
Когда collect
Тогда 2 строки, truncated=true
Дано maxBytes=5 и строка 12 байт
Когда collect
Тогда пустой результат, truncated=true

# AC-04 (QueryTemplateRegistryTest.renderRejectsDepthOutOfRange; GraphQueryCoreIT.traversalDepthIsBoundedByMaxDepth)
Дано шаблон с [:R*1..{maxDepth}]
Когда render(2,6)
Тогда текст содержит *1..2]
Когда render(0,6) или render(7,6)
Тогда IllegalArgumentException
Когда обход глубины 3 по цепочке из 4 узлов выполнен с maxDepth=2, затем 3
Тогда результаты ["1","2"] и ["1","2","3"]

# AC-05 (ResultBudgetTest.nonPositiveValuesAreRejected, BudgetedCollectorTest.nonPositiveBudgetIsRejected)
Дано maxDepth=0, maxNodes=0, timeout=0 или maxResponseBytes=0
Когда создаётся ResultBudget
Тогда IllegalArgumentException
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Защита от неограниченных обходов и больших ответов (ADR 0012) |
| Признак усечения по глубине | Won't | Не реализовано (🔴 Q-03) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java`, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java`, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java` | бюджет и потолки | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java` | усечение | 🟢 |
