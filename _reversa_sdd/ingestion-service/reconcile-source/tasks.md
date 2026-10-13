# reconcile-source: задачи

## Pré-requisitos
- [ ] `AdminOperations`/`AdminLock` (см. [`../tasks.md`](../tasks.md)); конфигурация `archrag.adapters.*.control-url`

## Tarefas

- [ ] T-01: `AdaptersProperties` и `AdapterControlClient` (`code`, `knows`, `snapshot` с таймаутами 5 с/10 мин, пропуск только 202/409)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:20-72`
  - Критерий готовности: AC-01, AC-02 на уровне клиента (`AdapterControlClientTest`)
  - Уверенность: 🟢
- [ ] T-02: эндпоинт `POST /admin/reconcile/{source}` (400/404 до замка, аудит `RECONCILE`, 502 по `AdapterUnavailableException`)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:101-115, 130-133`
  - Критерий готовности: AC-01..AC-03
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `AdapterControlClientTest` (3 теста)
- [ ] TT-02: `AdminControllerTest.reconcile*` (2 теста)
- [ ] TT-03: `AdminEndpointIT.reconcileProxiesToAdapterControlContext`, `reconcileUnknownSourceIs404UnreachableAdapterIs502AndScopeIsEnforced`, `reconcileTombstoneSurvivesRebuild`

## Ordem Sugerida
T-01 → T-02.

## Lacunas Pendentes (🔴)
П-15 (неаутентифицированный `/control/snapshot`) — решение за архитектором; см. [`../questions.md`](../questions.md) Q-07.
