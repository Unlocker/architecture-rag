# Блок-схемы: contracts/canonical-model

> Archaeologist, R.2a. Метки: 🟢 код · 🟡 выведено. Пути относительно `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`.

## 1. Проверка `UpsertRelation` (`command/UpsertRelation.java:25-42`) 🟢

```mermaid
flowchart TD
  A[new UpsertRelation] --> B{обязательные поля не null?}
  B -- нет --> E1[NullPointerException]
  B -- да --> C{type.allows fromLabel→toLabel?}
  C -- нет --> E2[IllegalArgumentException: недопустимые метки]
  C -- да --> D{validity != null и тип не temporal?}
  D -- да --> E3[IllegalArgumentException: validity только для temporal]
  D -- нет --> F[properties = Invariants.copyProperties]
  F --> G{properties содержит все identityProperties?}
  G -- нет --> E4[IllegalArgumentException: requires identity properties]
  G -- да --> H[готовая команда]
```

## 2. `RelationType.allows` и иерархия меток (`relation/RelationType.java:73-75`, `node/NodeLabel.java:54-61`) 🟢

```mermaid
flowchart TD
  A["allows(from, to)"] --> B{"любая из sources: from.isA(source)?"}
  B -- нет --> N[false]
  B -- да --> C{"любая из targets: to.isA(target)?"}
  C -- нет --> N
  C -- да --> Y[true]
  subgraph isA
    I1[current = this] --> I2{current == other?}
    I2 -- да --> IT[true]
    I2 -- нет --> I3[current = current.supertype]
    I3 --> I4{current == null?}
    I4 -- да --> IF[false]
    I4 -- нет --> I2
  end
```

## 3. Поиск авторитетности свойства (`authority/AuthorityMatrix.java:48-75`) 🟢

```mermaid
flowchart TD
  A["isAuthoritative(label, property, source)"] --> B{"есть правило свойства для label+property?"}
  B -- да --> C[множество правила свойства]
  B -- нет --> D["nodeAuthorities(label); суперметка не опрашивается"]
  D --> E{метка есть в матрице?}
  E -- нет --> F[пустое множество → false]
  E -- да --> G[множество правила узла]
  C --> H{source в множестве?}
  G --> H
  H -- да --> Y[true]
  H -- нет --> N[false: неавторитетное значение даёт Conflict, canonical не меняется]
```

## 4. `GitUrls.normalize` (`node/GitUrls.java:30-91`) 🟢

```mermaid
flowchart TD
  A[raw] --> B{null или blank?}
  B -- да --> X1[IAE: git url is blank]
  B -- нет --> C{"нет '://' и совпал scp-шаблон?"}
  C -- да --> D["scheme=https, host и path из scp"]
  C -- нет --> E["new URI(value)"]
  E -- ошибка синтаксиса --> X2["IAE: unrecognized git url (без cause)"]
  E --> F{схема}
  F -- "https, ssh, git" --> G[scheme=https]
  F -- http --> H[scheme=http]
  F -- иная --> X3[IAE: unsupported scheme]
  G --> I["port только для http/https"]
  H --> I
  D --> J
  I --> J{host пуст?}
  J -- да --> X4[IAE: no host]
  J -- нет --> K["trimPath: срез '/', '.git', хвостовых '/'"]
  K --> L{path пуст?}
  L -- да --> X5[IAE: no repository path]
  L -- нет --> M["scheme://host[:port]/path"]
```

## 5. Жизненный цикл `Lifecycle` (🟢, `node/Lifecycle.java:14-21`)

```mermaid
stateDiagram-v2
  [*] --> Current: deletedAt == null, isCurrent = true
  Current --> Closed: deletedAt != null, isCurrent = false
```

Переходы между состояниями выполняет проектор (R.2c); сам `Lifecycle` — иммутабельная запись, фиксирующая только допустимые сочетания полей 🟡.
