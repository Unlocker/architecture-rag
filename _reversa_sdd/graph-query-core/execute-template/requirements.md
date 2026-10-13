# execute-template: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Бюджет и усечение: [`../budget-limits/requirements.md`](../budget-limits/requirements.md).

## Visão Geral

`GraphQueryExecutor.execute(templateId, params, budget)` исполняет зарегистрированный шаблон на read-only сессии Neo4j и возвращает `QueryResult`. Это единственный путь чтения графа для приложений; Cypher от вызывающего не принимается.

## Regras de Negócio

- Неизвестный ID шаблона → `IllegalArgumentException("Unknown template: <id>")` до обращения к БД 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:61-64`).
- Ключи `params` должны в точности совпасть с `parameters` шаблона; лишний → `Unknown parameter`, недостающий → `Missing parameter`; значения в сообщение не попадают 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:133-146`).
- Параметр `limit` вызывающий передать не может: его имя зарезервировано, лишний ключ `limit` отвергается как неизвестный 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:53-56`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:150`).
- Перед первым исполнением шаблона `EXPLAIN` обязан дать `READ_ONLY`; `false` кэшируется и закрывает шаблон до перезапуска 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:99-124`).
- Исполнение — `session(READ).executeRead(...)` с `TransactionConfig.withTimeout(effective.timeout())`; сессия закрывается try-with-resources 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:73, 78-87`).
- Узел, связь или путь в значении колонки (в том числе во вложенных map и списках) → `IllegalStateException("<Тип> in result is forbidden: return projections like n {.gid, .name}")` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:172-188`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:123`).
- Ошибка Neo4j с кодом, содержащим `TransactionTimedOut` → `QueryTimeoutException(templateId)`; иная `Neo4jException` пробрасывается как есть 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:94-96, 126-131`).
- Ошибка `Neo4jException` во время `EXPLAIN` не фиксирует вердикт: шаблон не закрывается, следующий вызов повторит проверку 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:120-123`).
- Конструктор исполнителя не обращается к БД 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:45-48`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:103`).
- Результат содержит `templateId`, строки, `truncated`, `rowCount = rows.size()`, `elapsed` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:88-93`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java:19-28`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Исполнить шаблон и вернуть `QueryResult` | Must | AC-01 |
| RF-02 | Отказать до БД на неизвестный шаблон и неверный набор параметров | Must | AC-02 |
| RF-03 | Закрыть записывающий шаблон навсегда | Must | AC-03 |
| RF-04 | Запретить узлы, связи, пути в результате | Must | AC-04 |
| RF-05 | Перевести таймаут в `QueryTimeoutException` | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Только `AccessMode.READ` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:35-36` | 🟢 |
| Надёжность | Повтор тела транзакции драйвером безопасен: внутри нет внешних эффектов | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:79-87` | 🟢 |
| Производительность | `EXPLAIN` один раз на шаблон за жизнь процесса | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:100-105` | 🟢 |
| Наблюдаемость | `elapsed` измеряется вокруг `executeRead` (без `EXPLAIN`) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:77, 88-93` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (GraphQueryCoreIT.rowBudgetTruncates)
Дано шаблон "items" с параметрами {} и 20 узлов Item в графе
Когда execute("items", {}, бюджет maxNodes=50)
Тогда 20 строк, truncated=false, templateId="items", rowCount=20

# AC-02 (GraphQueryCoreIT.unknownTemplateAndBadParametersFailBeforeDatabase; публичный API — publicApiHasNoMethodAcceptingCypher)
Дано шаблон byGid с параметром gid
Когда execute("nope", {}), execute("byGid", {}), execute("byGid", {gid, x}) или execute("byGid", {gid, limit})
Тогда в каждом случае IllegalArgumentException и обращения к БД нет

# AC-03 (GraphQueryCoreIT.writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed)
Дано шаблоны CREATE / SET / DETACH DELETE, оканчивающиеся LIMIT $limit
Когда execute вызывается дважды подряд
Тогда оба раза IllegalStateException "not read-only"; число узлов и значение свойства не изменились

# AC-04 (GraphQueryCoreIT.nodesRelationshipsAndPathsInResultAreForbidden)
Дано шаблон возвращает n, [n], {k: n}, список связей или список путей
Когда execute
Тогда IllegalStateException "forbidden"

# AC-05 (GraphQueryCoreIT.timeoutGivesQueryTimeoutException)
Дано тяжёлый шаблон и бюджет timeout=100 мс
Когда execute
Тогда QueryTimeoutException быстрее 20 с
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Ловушки 1, 3, 7 `CLAUDE.md`; критерий готовности 6 PoC |
| Unit-тест исполнителя без Neo4j | Could | Сегодня только IT (Q-07) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java` | `GraphQueryExecutor` | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java`, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTimeoutException.java` | результат и ошибка | 🟢 |
