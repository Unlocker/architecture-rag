# explain-provenance: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4.

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `ExplainProvenanceTool.explainProvenance` | `(String gid, String property) -> ExplainProvenanceResult` | `IllegalArgumentException`: `gid must be a UUID`, `property is invalid`, `asset not found` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:41-88 🟢 |
| `ExplainProvenanceResult` / `RecordRef` | record | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:19-51 🟢 |
| `AssetTemplates.EXPLAIN_PROVENANCE` | `QueryTemplate`, параметр `gid`, `ResultKind.NODES` | `{gid,type,isCurrent,deletedAt,records[]}` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:181-210 🟢 |
| `AuthorityMatrix.defaults()` | `canonical-model` | `nodeAuthorities(NodeLabel)`, `propertyAuthorities(NodeLabel, String)` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:28, 111-120 🟢 |

## Fluxo Principal

1. `normalizeGid(gid)` и `normalizeProperty(property)` до обращения к графу (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:59-60) 🟢.
2. Бюджет — потолки конфигурации целиком (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:61-67) 🟢.
3. `queries.execute("explain_provenance", {gid}, budget)`; пусто → `asset not found` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:69-73) 🟢.
4. `type` — метка из строки; `masters = authorities(type, property)`: перебирает `NodeLabel.values()`, сравнивает `label()` с `type`; найдено → узловые или свойственные мастера; нет → `Set.of()` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:75-76, 110-120) 🟢.
5. Для каждой записи `RecordRef`: поля шаблона; `confidence` — `Double` только если число; `authoritative` — `source` в мастерах; `conflictProperties` — из `conflicts` (пусто, если `null`), при `property != null` оставляется только он (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:122-150) 🟢.
6. `open` = существует запись с непустыми `conflictProperties`; `conflictState` = `OPEN`/`NONE`; `authorities` = отсортированные имена (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:78-87) 🟢.

## Fluxos Alternativos

- **Закрытый узел / tombstone** — отдаётся без фильтра (шаблон не фильтрует `isCurrent` и `active`) (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:171-180) 🟢.
- **Неизвестная метка узла** — `authorities=[]`, все `authoritative=false` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:110-120; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:121-130) 🟢.
- **`property` не из матрицы** — `propertyAuthorities` определяет поведение (вне модуля) 🟡.

## Dependências

- `canonical-model`: `AuthorityMatrix`, `NodeLabel`, `SourceSystemCode` — транзитивно через `graph-query-core` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:3-5; Q-07) 🟡.
- Данные конфликтов на ребре `ASSERTS` пишет `graph-projector` ([`../../graph-projector/`](../../graph-projector/)).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Фильтр по `property` делается в Java, а не в Cypher (строка не попадает в запрос) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:134-136 | 🟢 |
| Свой экземпляр `AuthorityMatrix.defaults()` в tool (3-й в системе) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:28 | 🟢 (риск Н-10, Q-07) |
| Fail closed на неизвестной метке | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:110-119 | 🟢 |

## Estado Interno

Нет (матрица иммутабельна, создаётся в конструкторе).

## Observabilidade

Шаблон `explain_provenance` в аудите; нет собственных метрик.

## Riscos e Lacunas

- 🟡 Три экземпляра `AuthorityMatrix.defaults()` (Н-10): при появлении конфигурации правил tool разойдётся с проектором (Q-07).
- 🟡 `confidence` может быть `null`, если ребро не несёт числа (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:144).
