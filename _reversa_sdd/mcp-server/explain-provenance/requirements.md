# explain-provenance: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. Матрица авторитетности: [`../../canonical-model/`](../../canonical-model/).

## Visão Geral

Tool `explain_provenance` отвечает на вопрос «откуда это известно и кому верить»: по `gid` актива (и, при желании, по имени свойства) возвращает записи источников с версией, `fetchedAt`, `contentHash`, `confidence`/`authority` ребра `ASSERTS`, признаком авторитетного источника по authority matrix, активностью (tombstone) и открытыми конфликтами. Значения свойств не возвращаются: расхождение показано парой записей (мастер и диссидент) и именами спорных свойств.

## Regras de Negócio

- `gid` обязателен и должен быть UUID, нормализуется; ошибка не содержит значения 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:90-97).
- `property` необязателен; пусто/пробелы = отсутствует; иначе должен соответствовать `^[A-Za-z][A-Za-z0-9_]{0,63}$`, иначе `property is invalid` (значение не эхоится) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:24, 99-108; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:142-161).
- Нет строки шаблона → `asset not found` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:69-73).
- Закрытый узел и неактивные (tombstone) записи отдаются: `isCurrent=false`, `deletedAt`, `active=false` 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:171-180; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:131-141).
- Авторитетные источники (`authorities`): без `property` — мастера узла `AuthorityMatrix.nodeAuthorities(label)`, с `property` — `propertyAuthorities(label, property)` (правило свойства перекрывает правило узла); неизвестная метка → пусто (fail closed) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:28, 110-120; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:95-130).
- `authoritative` записи = её `source` входит в имена мастеров; `authorities` отдаются отсортированными именами `SourceSystemCode` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:85, 146).
- `conflictProperties` записи — имена свойств с ребра `ASSERTS`; при заданном `property` остаётся только он (если он в списке) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:129-150; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:95-109).
- `conflictState = OPEN`, если хотя бы у одной записи (после фильтра) есть конфликт, иначе `NONE` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:78, 86).
- Значения свойств в ответе отсутствуют 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:5-7; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:171-180).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Записи источников актива с версией, временем, хешем, ребром `ASSERTS` | Must | AC-01 |
| RF-02 | Признак авторитетности записи по authority matrix (узла или свойства) | Must | AC-01, AC-02 |
| RF-03 | Открытые конфликты (`OPEN`/`NONE`), фильтр по `property` | Must | AC-02 |
| RF-04 | Tombstone-записи отдаются неактивными; закрытый узел — `isCurrent=false` | Should | AC-03 |
| RF-05 | Невалидный gid/property и неизвестный актив — ошибки tool без эха | Must | AC-04 |
| RF-06 | Доступ только со scope `architecture.read` | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Значения свойств и введённые gid/property не попадают в ответ/ошибку | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:5-7; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:90-108 | 🟢 |
| Безопасность | `property` проходит allowlist-regexp; в Cypher не подставляется (фильтрация в Java) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:24, 134-136 | 🟢 |
| Производительность | Один запрос шаблона; latency измерена: 0,39 / 0,51 с (клиентский p95) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:69-70; docs/04-poc-measurements.md:9 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (ExplainProvenanceToolTest.marksAuthoritativeSourceAndOpenConflict; ExplainProvenanceIT.twoSourcesShowMasterAndOpenCriticalityConflict; F4AcceptanceIT.s2_assetFromTwoSourcesShowsMasterAndConflict)
Дано актив из двух источников, мастер и диссидент расходятся по criticality
Когда explain_provenance gid=<UUID>
Тогда records содержит обе записи, у мастера authoritative=true, у диссидента conflictProperties содержит criticality, conflictState=OPEN, значений свойств в ответе нет

# AC-02 (ExplainProvenanceToolTest.propertyFiltersConflictsAndUsesPropertyAuthorities, propertyRuleOverridesNodeAuthorities; ExplainProvenanceIT.propertyFilterKeepsOnlyThatPropertyConflict, agreeingSourcesHaveNoConflict; F4AcceptanceIT.s3_propertyFilterKeepsOnlyThatPropertyConflict)
Дано property="criticality"
Когда explain_provenance
Тогда authorities вычисляются по правилу свойства, у записей остаётся только конфликт по этому свойству
Дано источники согласны
Тогда conflictState=NONE

# AC-03 (ExplainProvenanceToolTest.tombstoneRecordIsReturnedInactive; ExplainProvenanceIT.tombstonedSourceIsInactiveAndConflictIsCleared; F4AcceptanceIT.s4_tombstoneInOneSourceKeepsAssetCurrent, s7_tombstoneOfMasterClosesAssetEvenWithActiveSupplementary)
Дано запись одного источника закрыта (tombstone)
Когда explain_provenance
Тогда запись присутствует с active=false и deletedAt; узел остаётся isCurrent=true, пока жива авторитетная запись

# AC-04 (ExplainProvenanceToolTest.rejectsInvalidGidAndPropertyWithoutEchoOrGraphAccess, emptyResultIsAssetNotFoundWithoutGid, blankPropertyIsTreatedAsAbsent; ExplainProvenanceIT.unknownGidIsAssetNotFound; F4AcceptanceIT.s5_unknownGidIsToolErrorWithoutStacktrace)
Дано gid="не-UUID", либо property="1bad;DROP", либо неизвестный UUID
Когда explain_provenance
Тогда isError=true: "gid must be a UUID" / "property is invalid" / "asset not found"; значения ввода отсутствуют; для невалидного ввода граф не опрашивался
Дано property="   "
Тогда он трактуется как отсутствующий

# AC-05 (F4AcceptanceIT.s6_accessWithoutScopeOrTokenIsDenied; McpSecurityTest.toolCallWithOtherScopeIsForbidden)
Дано токен без architecture.read либо без токена
Когда tools/call explain_provenance
Тогда 403 либо 401
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-03, RF-05, RF-06 | Must | Объяснимость фактов (F4) |
| RF-04 | Should | Диагностика tombstone |
| Ручное закрытие конфликта | Won't | Операции нет (П-22) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java, access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java | tool и ответ | 🟢 |
| access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java | шаблон `explain_provenance` | 🟢 |
