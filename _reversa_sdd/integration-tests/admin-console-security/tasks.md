# admin-console-security: задачи

## Pré-requisitos
- [ ] Каркас модуля, `McpTestJwt`, `EamApiSupport`, заглушки ([`../tasks.md`](../tasks.md) T-01..T-05)
- [ ] `ingestion-service` (admin REST, `AdminLock`, аудит, метрики), `access/admin-console` (графовая и журнальная части), миграции V1–V7 с ролью `archrag_console_ro`

## Tarefas

- [ ] T-01: `AdminEndpointIT` — ленивый `seed()` из четырёх источников, ручной `processAllReceived`, 13 тестов (rebuild, replay, crosswalks, токены, замок, reconcile)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:54-477`
  - Критерий готовности: AC-01..AC-05
  - Уверенность: 🟢
- [ ] T-02: `AdminConsoleIT` — два контекста консоли, засев графа Cypher-ом, восемь сценариев
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:53-334`
  - Критерий готовности: AC-06
  - Уверенность: 🟢
- [ ] T-03: `SyncConsoleIT` — read-роль PG, read-аудит, журнал, источники, конфликты, кандидаты, аудит операций (14 тестов)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:37-376`
  - Критерий готовности: AC-07
  - Уверенность: 🟢
- [ ] T-04: `IngestionMetricsIT` — gauge лага и открытой DLQ
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java:19-94`
  - Критерий готовности: AC-08
  - Уверенность: 🟢
- [ ] T-05: 🔴 негативы безопасности — консоль (`/api/graph/*`, `/api/identity/*`, `/api/sync/dlq|sources`, `/api/audit` без токена, чужой audience, неверный `azp`), `/admin/crosswalks` и `/admin/reconcile/*` (401, audience, `azp`)
  - Origin: [`../questions.md`](../questions.md) Q-01
  - Критерий готовности: решение архитектора; тесты добавлены либо пробел принят и записан
  - Уверенность: 🔴
- [ ] T-06: 🔴 пересмотреть тесты, закрепляющие недостижимые состояния: `SyncRun SUCCEEDED`, `replayedAt`, статус `OK` в `admin_audit`
  - Origin: [`../questions.md`](../questions.md) Q-07, Q-08, Q-32
  - Критерий готовности: фикстуры совпадают с тем, что пишет прод, либо решение в ADR
  - Уверенность: 🔴
- [ ] T-07: 🟡 отделить сид `AdminEndpointIT` от мутирующих тестов (общее состояние, полный `DETACH DELETE`)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:116, 134-136, 325`
  - Критерий готовности: падение теста посередине не оставляет пустой граф следующим
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: каждый класс запускается отдельно (`-Dit.test=<Class>`), порядок методов не нужен; `AdminEndpointIT` полагается на ленивый `seed()`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:116`
  - Критерий готовности: зелёный на машине с Docker; без Docker заявлять нельзя
  - Уверенность: 🟢

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04; T-05..T-07 после решения.

## Lacunas Pendentes (🔴)
Q-01, Q-07, Q-08 — [`../questions.md`](../questions.md).
