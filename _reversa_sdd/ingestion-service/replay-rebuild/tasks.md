# replay-rebuild: задачи

## Pré-requisitos
- [ ] `JournalReader.read` с `JournalQuery(excludeReplays)`, `EventJournal.append` (event-journal), `RawPayloadStore.get`, `EventProcessor.process`
- [ ] `AdminOperations` / `AdminLock` (см. [`../tasks.md`](../tasks.md))

## Tarefas

- [ ] T-01: `ReplayService.replay` + `replayOne`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:63-100`
  - Критерий готовности: AC-01, AC-02; статусы `SKIPPED_SNAPSHOT_MARKER`, `SKIPPED_NO_RAW`; страницы по 200
  - Уверенность: 🟢
- [ ] T-02: `RebuildService.rebuild` (WIPE по 10000 + replay без фильтров)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18-39`
  - Критерий готовности: AC-03, AC-05
  - Уверенность: 🟢
- [ ] T-03: эндпоинты `/admin/replay`, `/admin/rebuild` с валидацией и аудитом
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:61-92, 140-156`
  - Критерий готовности: AC-02n, AC-04; `summary = {total, statuses}`
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `AdminControllerTest.replayWithScopeReturnsResultAndPassesActor`, `replayRejectsBadParameters`, `rebuildRequiresConfirm`, `rebuildWithConfirmRuns`
- [ ] TT-02: `AdminEndpointIT` (rebuild эквивалентен, отложенные связи, replay идемпотентен, пустой диапазон, tombstone переживает rebuild)
- [ ] TT-03: 🔴 нет теста на replay при строке с битым raw / hash mismatch (Q-04)

## Ordem Sugerida
T-01 → T-02 → T-03.

## Lacunas Pendentes (🔴)
Q-04 — [`../questions.md`](../questions.md).
