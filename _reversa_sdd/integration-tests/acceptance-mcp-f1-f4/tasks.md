# acceptance-mcp-f1-f4: задачи

## Pré-requisitos
- [ ] Каркас модуля, образы, `McpTestJwt`, `EamApiSupport`, заглушки ([`../tasks.md`](../tasks.md) T-01..T-05; [`../../source-stubs/tasks.md`](../../source-stubs/tasks.md))
- [ ] Рабочие `adapters/*`, `ingestion-service` с диспетчером, `access/mcp-server` с пятью tools и `ToolScopeFilter`

## Tarefas

- [ ] T-01: общий каркас класса — `static {}` контейнеров, `@DynamicPropertySource`, HS256-`JwtDecoder`, `@BeforeAll` (адаптеры, snapshot, ожидание, `mcp-reader`, запуск MCP), `@AfterAll`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:75-236`
  - Критерий готовности: после `@BeforeAll` очередь `inbox_event` пуста, `QUARANTINED = 0`, индексы Neo4j `ONLINE`, MCP отвечает на `tools/list`
  - Уверенность: 🟢
- [ ] T-02: F1 — сценарии `s1`..`s8` (`tools/list`, полнотекстовый и точный поиск, фильтры, `limit`, карточка, ошибка неизвестного gid, 401/403)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:240-359`
  - Критерий готовности: AC-01, AC-06 (для `search_assets`)
  - Уверенность: 🟢
- [ ] T-03: F2 — сценарии `s1`..`s7` и `s2b` (footprint, окружение, смена deploy map, замена хоста, удаление, commit/репозиторий, ошибки, 401/403)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:246-428`
  - Критерий готовности: AC-02, AC-06; 🔴 уникальные значения `@Order` (Q-04)
  - Уверенность: 🟢 (🔴 порядок)
- [ ] T-04: F3 — сценарии `s1`..`s8` (схема tool, trace вниз и вверх, impact с провенансом, фильтр окружения, шесть невалидных входов, 401/403, задержки)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:252-417`
  - Критерий готовности: AC-03, AC-04, AC-06, AC-07
  - Уверенность: 🟢
- [ ] T-05: F4 — подготовка (`@Primary EventProcessor` с `ScmItSystemMapper`, `linkAndLoad` с crosswalk до первого события) и сценарии `s1`..`s7`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:97-230, 236-250, 318-448`
  - Критерий готовности: AC-05, AC-06; crosswalk применяется ответом `APPLIED` до загрузки записей
  - Уверенность: 🟢
- [ ] T-06: 🔴 покрыть пробелы — `stale=true`, неверный audience/issuer/exp, `tools/list` без scope, полный `tool-scopes`, `get_asset`/`ping` без scope
  - Origin: [`../questions.md`](../questions.md) Q-01, Q-06, Q-12, Q-25
  - Критерий готовности: решение архитектора по каждому пункту, тесты добавлены
  - Уверенность: 🔴
- [ ] T-07: 🟡 вынести общий каркас (`post`, `awaitTrue`, `count`, `scalarLong`, `startMcp`, `awaitIdle`) из четырёх классов
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:363-427`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:446-512`
  - Критерий готовности: дубли удалены без новых библиотек
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: прогон `./mvnw -B -ntp verify -Pintegration-tests -Dit.test=F1AcceptanceIT` (и F2, F3, F4) на машине с Docker
  - Origin: CLAUDE.md репозитория (раздел «Сборка и тесты»)
  - Критерий готовности: каждый класс зелёный целиком; если Docker недоступен, прогон `*IT` не заявляется
  - Уверенность: 🟢
- [ ] TT-02: 🟡 усилить замеры F3 (больше выборка, чем 10; холодный вызов — первый в классе)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:250, 497-500`; Q-17
  - Критерий готовности: p95 осмыслен при выбранном n
  - Уверенность: 🟡

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05; T-06, T-07 после решения.

## Lacunas Pendentes (🔴)
Q-01, Q-04, Q-05, Q-06 — [`../questions.md`](../questions.md).
