# explain-provenance: задачи

## Pré-requisitos
- [ ] Шаблон `explain_provenance` зарегистрирован (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:66-69)
- [ ] `AuthorityMatrix` доступен из `canonical-model`

## Tarefas

- [ ] T-01: `ExplainProvenanceTool.explainProvenance`: нормализация gid/property, запрос, сборка ответа
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:41-88
  - Критерий готовности: AC-01..AC-04; `ExplainProvenanceToolTest` (8 тестов)
  - Уверенность: 🟢
- [ ] T-02: Вычисление мастеров узла/свойства и признака `authoritative`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:110-150
  - Критерий готовности: правило свойства перекрывает правило узла; неизвестная метка → пусто
  - Уверенность: 🟢
- [ ] T-03: `ExplainProvenanceResult` / `RecordRef` (без значений свойств)
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:19-51
  - Критерий готовности: поля из [`../contracts.md`](../contracts.md) §1.4
  - Уверенность: 🟢
- [ ] T-04: Шаблон `explain_provenance`
  - Origin: access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:171-210
  - Критерий готовности: `ExplainProvenanceIT` зелёный на Neo4j
  - Уверенность: 🟢
- [ ] T-05: Scope-запись
  - Origin: access/mcp-server/src/main/resources/application.yml:33
  - Критерий готовности: без `architecture.read` — `403`
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `ExplainProvenanceToolTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceToolTest.java:71-171)
- [ ] TT-02: `ExplainProvenanceIT` (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:178-274)
- [ ] TT-03: `F4AcceptanceIT` S1-S7 (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:319-440)
- [ ] TT-04: 🔴 нет `*McpTest` для `explain_provenance` (сквозной вызов tool проверяется только в `F4AcceptanceIT`)

## Ordem Sugerida
1. T-04 → 2. T-03 → 3. T-02 → 4. T-01 → 5. T-05.

## Lacunas Pendentes (🔴 и 🟡)
- Q-07 (транзитивная зависимость и три матрицы) — [`../questions.md`](../questions.md).
