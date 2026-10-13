# sync-reconcile: задачи

## Pré-requisitos
- [ ] Каркас модуля и заглушки ([`../tasks.md`](../tasks.md); [`../../source-stubs/tasks.md`](../../source-stubs/tasks.md))
- [ ] Рабочие `adapter-core` (`Poller`, webhook, control), адаптеры, `ingestion-service` (диспетчер, admin REST), `graph-projector` (`Reconciler`)

## Tarefas

- [ ] T-01: `AdapterInboxIT` — реальный adapter EAM с webhook и PG+S3; webhook принят/повтор/отклонён; маркер snapshot последним
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java:39-151`
  - Критерий готовности: AC-02 (приём), AC-02 (отказ 401 без записи в журнал)
  - Уверенность: 🟢
- [ ] T-02: `JournalDispatcherIT` — `@SpringBootTest` с диспетчером `100ms/1/1s`; шесть сценариев
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:60-297`
  - Критерий готовности: AC-04 (маркер), AC-07
  - Уверенность: 🟢
- [ ] T-03: `ReconciliationIT` — ручная сборка `EventProcessor` с `Reconciler`, `FailingAfter`, `RecordingJournal`; шесть сценариев
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:64-400`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-04: `SyncAcceptanceIT` — одиннадцать `@Order` сценариев К1–К4 с реальными часами и HS256-токеном admin
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:84-543`
  - Критерий готовности: AC-01, AC-02 (p95), AC-03, AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-05: `EamKeyMigrationIT` — пять упорядоченных сценариев миграции ключа
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java:75-324`
  - Критерий готовности: AC-08
  - Уверенность: 🟢
- [ ] T-06: 🔴 тест транзиентного сбоя Neo4j с проверкой отсутствия побочных эффектов (ловушка 7) и проверка «курсор адаптера после коммита»
  - Origin: [`../questions.md`](../questions.md) Q-03, Q-10
  - Критерий готовности: решение архитектора, тест добавлен либо пробел принят
  - Уверенность: 🔴
- [ ] T-07: 🟢 [R.8: ответ владельца, вопрос 14] измерение polling-границы «интервал + 5 с» (критерий 2, `k2c` без замера; Q-35)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:348-362`; `docs/00-vision.md:799`
  - Критерий готовности: замер задержки polling с порогом интервал + 5 с
  - Уверенность: 🔴

## Tarefas de Teste

- [ ] TT-01: прогон `-Dit.test=ReconciliationIT`, `JournalDispatcherIT`, `AdapterInboxIT` по отдельности допустим; `SyncAcceptanceIT` и `EamKeyMigrationIT` — только целиком
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:84-85`
  - Критерий готовности: классы зелёные; без Docker `*IT` не запускались — заявлять нельзя
  - Уверенность: 🟢

## Ordem Sugerida
T-01 → T-03 → T-02 → T-04 → T-05; T-06, T-07 после решения.

## Lacunas Pendentes (🔴)
Q-03, Q-10, Q-35 — [`../questions.md`](../questions.md).
