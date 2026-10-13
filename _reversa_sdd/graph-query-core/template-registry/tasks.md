# template-registry: задачи

## Pré-requisitos
- [ ] `ResultKind` и `QueryLimits` реализованы (см. [`../tasks.md`](../tasks.md) T-02)

## Tarefas

- [ ] T-01: Конструктор `QueryTemplate`: обязательные поля, зарезервированный `limit`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:42-58`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-02: `validate`: плейсхолдеры, `LIMIT $limit`, множество параметров
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:60-86`
  - Критерий готовности: AC-01, AC-02 (чужой плейсхолдер)
  - Уверенность: 🟢
- [ ] T-03: Проверка ограниченности обходов и границы `{maxDepth}`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:30-40, 87-104`
  - Критерий готовности: AC-02 (пять неограниченных форм отвергнуты)
  - Уверенность: 🟢
- [ ] T-04: `maxLiteralDepth`, `hasDepthPlaceholder`, `render`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:107-138`
  - Критерий готовности: `renderRejectsDepthOutOfRange`, `literalDepthAboveLimitIsRejected`
  - Уверенность: 🟢
- [ ] T-05: `QueryTemplateRegistry.of/find/ids/limits`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:17-61`
  - Критерий готовности: AC-03, AC-04
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `QueryTemplateRegistryTest` — 13 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:20-112`)
- [ ] TT-02: `*TemplatesTest.*Registrable` — регистрация шаблонов каталога (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplatesTest.java:14-26`; `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplatesTest.java:18`; `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplatesTest.java:18`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05.

## Lacunas Pendentes (🔴)
Нет собственных 🔴; 🟡 Q-12 (регулярные выражения вместо парсера).
