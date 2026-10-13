# template-registry: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Visão Geral

`QueryTemplate` проверяет Cypher-текст в момент создания, `QueryTemplateRegistry` собирает шаблоны в неизменяемый реестр с проверкой ID и литеральной глубины. Проверки статические, без БД; `EXPLAIN` выполняется позже, исполнителем.

## Regras de Negócio

- `id`, `cypher` не пустые; `kind` не `null` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:43-51`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:100`).
- Имя параметра `limit` зарезервировано: объявить его в `parameters` нельзя 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:53-56`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:63`).
- Допустим единственный плейсхолдер `{maxDepth}` (пробелы внутри скобок игнорируются), и не более одного 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:61-73`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:36`). Литерал map Cypher (`{key: ...}`) плейсхолдером не считается 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:30`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:43`).
- Текст оканчивается на `LIMIT $limit` (допустим `;` и пробелы в конце) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:32-33, 74-76`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:48`).
- Множество `$имя` в тексте (без `limit`) равно объявленным `parameters` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:77-86`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:55`).
- Каждый variable-length путь `[...*...]` имеет верхнюю границу: число или `{maxDepth}`; отвергаются `[*]`, `[*1..]`, `[r:T*]`, `[:A|B*2..]`, `[* ..]` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:38-40, 87-99`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:69`).
- `{maxDepth}` допустим только как верхняя граница пути (число использований как границы равно числу плейсхолдеров) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:100-104`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:106`).
- Реестр: ID уникальны (`Duplicate template id`); `maxLiteralDepth() ≤ limits.maxDepth` (`literal depth N exceeds limit M`) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:35-42`; тесты `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:20, 89`).
- Реестр неизменяем после создания; `ids()` возвращает копию 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:23, 53-55`).
- Регулярные выражения разбирают типичные формы; экзотические (комментарии, переносы внутри скобок связи) могут не распознаться и тогда обход не проверен статически; страховка — таймаут, лимит строк и `EXPLAIN` 🟡 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:34-36`; Q-12).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Отвергнуть шаблон без `LIMIT $limit`, с лишним/недостающим параметром, с зарезервированным `limit` | Must | AC-01 |
| RF-02 | Отвергнуть неограниченный обход и чужие плейсхолдеры | Must | AC-02 |
| RF-03 | Отвергнуть дубликат ID и литеральную глубину выше потолка | Must | AC-03 |
| RF-04 | Все 14 шаблонов каталога регистрируются вместе | Must | AC-04 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Ловушка 2: нет обхода без верхней границы и нет `*1..$depth` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:87-104` | 🟢 |
| Надёжность | Ошибка конфигурации шаблона проявляется при старте приложения, а не при запросе | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:42-58` | 🟢 |
| Сопровождаемость | Сообщения ошибок содержат ID шаблона | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:65-66, 75, 84-85` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (QueryTemplateRegistryTest.templateWithoutLimitIsRejected, declaredAndUsedParametersMustMatch, limitParameterCannotBeDeclared, nullKindIsRejected)
Дано текст без LIMIT $limit, либо $x в тексте без объявления, либо объявленный параметр, не используемый в тексте
Когда создаётся QueryTemplate
Тогда IllegalArgumentException с ID шаблона

# AC-02 (QueryTemplateRegistryTest.unboundedTraversalIsRejected, boundedTraversalIsAccepted, foreignPlaceholderIsRejected, mapLiteralIsNotAPlaceholder, depthPlaceholderOutsideTraversalIsRejected)
Дано [*], [*1..], [r:DEPENDS_ON*], [:A|B*2..] или {other} в тексте
Когда создаётся QueryTemplate
Тогда IllegalArgumentException
Дано [:DEPENDS_ON*1..{maxDepth}] или [:DEPENDS_ON*1..3]
Когда создаётся QueryTemplate
Тогда шаблон принят (hasDepthPlaceholder true / false)

# AC-03 (QueryTemplateRegistryTest.duplicateIdIsRejected, literalDepthAboveLimitIsRejected, registeredTemplateIsFound)
Дано два шаблона с одним ID, либо литерал *1..1000 при потолке 6
Когда QueryTemplateRegistry.of
Тогда IllegalArgumentException ("depth" в сообщении для второго случая)
Дано шаблон с *1..6 при потолке 6
Когда QueryTemplateRegistry.of
Тогда find("t") присутствует

# AC-04 (AssetTemplatesTest.searchTemplateIsValidAndRegistrable, ConsoleTemplatesTest.allTemplatesAreValidAndRegistrableAlongsideAssetTemplates, DependencyTemplatesTest.templatesAreValidAndRegistrable)
Дано шаблоны Asset, Dependency, Runtime и Console
Когда QueryTemplateRegistry.of с типовыми потолками
Тогда реестр создан, ID не конфликтуют
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04 | Must | Ловушки 1 и 2 `CLAUDE.md` |
| Разбор Cypher настоящим парсером | Could | Сейчас регулярные выражения (Q-12) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java` | `QueryTemplate` | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java` | `QueryTemplateRegistry` | 🟢 |
