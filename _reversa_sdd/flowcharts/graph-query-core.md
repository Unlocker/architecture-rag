# Блок-схемы: access/graph-query-core

> Archaeologist, R.2d. Пути относительно `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/`. Шаблоны и их параметры — `code-analysis.md` §X1.3.

## `GraphQueryExecutor.execute` (`GraphQueryExecutor.java:60-97`) 🟢

```mermaid
flowchart TD
  A["execute(templateId, params, budget)"] --> F{"шаблон в реестре?"}
  F -- нет --> E1["IllegalArgumentException: Unknown template"]
  F -- да --> P{"ключи params == template.parameters?"}
  P -- нет --> E2["IllegalArgumentException: Unknown / Missing parameter"]
  P -- да --> B["effective = (budget ?? потолки).clampTo(limits)"]
  B --> R["cypher = render(effective.maxDepth, limits.maxDepth)"]
  R --> K["maxRows = kind: NODES→maxNodes, PATHS→maxPaths;\nlimit = maxRows + 1"]
  K --> V{"вердикт EXPLAIN в кэше?"}
  V -- нет --> X["EXPLAIN cypher (READ-сессия)"]
  X -- "READ_ONLY" --> C1["кэш: true"]
  X -- "иной тип" --> C2["кэш: false"]
  X -- "Neo4jException" --> T0["translate (вердикт не кэшируется)"]
  C1 --> RUN
  V -- "true" --> RUN
  V -- "false" --> E3["IllegalStateException: not read-only"]
  C2 --> E3
  RUN["session(READ).executeRead(tx → collect(RowIterator), timeout)"] --> N{"результат"}
  N -- "Node / Relationship / Path в строке" --> E4["IllegalStateException: forbidden"]
  N -- "Neo4jException TransactionTimedOut" --> E5["QueryTimeoutException"]
  N -- "другой Neo4jException" --> E6["пробрасывается как есть"]
  N -- ok --> Q["QueryResult(rows, truncated, rowCount, elapsed)"]
```

## `BudgetedCollector.collect` (`BudgetedCollector.java:41-58`) 🟢

```mermaid
flowchart TD
  S["collect(iterator)"] --> W{"read <= maxRows и hasNext?"}
  W -- нет --> OK["Collected(rows, truncated=false)"]
  W -- да --> N["row = next(); read++"]
  N --> L{"read > maxRows?"}
  L -- да --> TR1["Collected(rows, truncated=true)\n(лишняя строка не добавляется)"]
  L -- нет --> SZ["bytes += JSON-размер(row)"]
  SZ --> BY{"bytes > maxBytes?"}
  BY -- да --> TR2["Collected(rows, truncated=true)\n(строка не добавляется)"]
  BY -- нет --> ADD["rows.add(row)"] --> W
```

## Валидация `QueryTemplate` (`QueryTemplate.java:42-105`) 🟢

```mermaid
flowchart TD
  C["new QueryTemplate(id, cypher, params, kind)"] --> I{"id, cypher, kind заданы?\nlimit не в params?"}
  I -- нет --> X1["IllegalArgumentException"]
  I -- да --> PH{"плейсхолдеры {…} только {maxDepth}, не больше одного?"}
  PH -- нет --> X1
  PH -- да --> LM{"оканчивается LIMIT $limit?"}
  LM -- нет --> X1
  LM -- да --> PR{"$param в тексте (без limit) == params?"}
  PR -- нет --> X1
  PR -- да --> VL{"каждый [..*..] имеет верхнюю границу\n(число или {maxDepth})?"}
  VL -- нет --> X1
  VL -- да --> UD{"{maxDepth} встречается только как граница пути?"}
  UD -- нет --> X1
  UD -- да --> OK["шаблон создан"]
```

В реестре дополнительно: `maxLiteralDepth() ≤ limits.maxDepth` и уникальность ID (`QueryTemplateRegistry.java:32-45`). `render` отвергает `depth ∉ 1..ceiling` (`QueryTemplate.java:133-138`).
