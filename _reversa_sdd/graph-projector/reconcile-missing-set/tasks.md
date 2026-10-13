# graph-projector / reconcile-missing-set: задачи

> Сокращения путей как в [`../requirements.md`](../requirements.md). Общие предпосылки — [`../tasks.md`](../tasks.md).

## Задачи

- [ ] **T-01. Порт `ReconciliationTrigger` и `NOOP`.**
  Origin: `GP/ReconciliationTrigger.java:8-20`. Критерий готовности: `EventProcessor` собирается с `NOOP` (короткие конструкторы тестов). Уверенность: 🟢.
- [ ] **T-02. Конструктор и `Report`.** Шесть зависимостей с `requireNonNull`; `Report(int, int, int, int)`.
  Origin: `GP/Reconciler.java:70-86`. Критерий готовности: `ReconcilerTest` компилируется и проходит. Уверенность: 🟢.
- [ ] **T-03. Проверки полноты.** Источник известен; `eventCount < objectCount` → `IllegalStateException`; начало окна `touched`.
  Origin: `GP/Reconciler.java:100-114`. Критерий готовности: AC-02, AC-09, AC-10. Уверенность: 🟢 (обязательность `objectCount` — 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 6)] Q-05).
- [ ] **T-04. Цикл по активным записям и счётчики.**
  Origin: `GP/Reconciler.java:115-143`. Критерий готовности: AC-01, AC-03, AC-07; IT `missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive`. Уверенность: 🟢.
- [ ] **T-05. `journalTombstone`, `rawDelete`, `quote`.** Идемпотентный append, шаги статусов по `ordinal()`/`RETRYING`, формат raw (контракт — [`../contracts.md`](../contracts.md) §6.1).
  Origin: `GP/Reconciler.java:156-182, 196-220`. Критерий готовности: AC-05, AC-06. Уверенность: 🟢 (ручной JSON — 🟡 Q-07; строка в `QUARANTINED` — 🟡 Q-12).
- [ ] **T-06. `retract`.** Пропуск при `OLD`, иначе `identity.find` → `conflicts.retract`.
  Origin: `GP/Reconciler.java:146-154`. Критерий готовности: AC-08. Уверенность: 🟢.
- [ ] **T-07. `finishTombstone`.**
  Origin: `GP/Reconciler.java:184-194`. Критерий готовности: AC-05, AC-07. Уверенность: 🟢.
- [ ] **T-08. Подключение триггера в `EventProcessor.snapshotComplete`.** Статусы до `RESOLVED`, вызов с `data.sourceId`, `RuntimeException` → `RETRYING`/`RECONCILIATION_TRIGGER_FAILED`.
  Origin: `GP/EventProcessor.java:289-300`. Критерий готовности: `snapshotCompleteIsHandedToReconciliationAndNotProjected`. Уверенность: 🟢.

## Тесты

- [ ] **TT-01.** `ReconcilerTest` (12 тестов): список в [`requirements.md`](requirements.md). Origin: `GPT/ReconcilerTest.java`. Уверенность: 🟢.
- [ ] **TT-02.** `ReconciliationIT` (Docker): `missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive`, `missedCreateAppearsAfterSnapshot`, `failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun`, `quarantinedSnapshotObjectIsNotTreatedAsMissing`, `reprocessingTheSameMarkerChangesNothing`, `objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun`. Origin: `IT/ReconciliationIT.java`. Уверенность: 🟢.
- [ ] **TT-03 (пробел).** Нет теста: маркер без `objectCount`; маркер с неизвестным источником через `EventProcessor`; `finishTombstone` для строки в `QUARANTINED`; replay строки `reconcile:…`. Уверенность: 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 6)] (Q-05, Q-11, Q-12).

## Порядок

T-01 → T-02 → T-03 → T-05 → T-06 → T-07 → T-04 → T-08.

## Открытые 🔴

[`../questions.md`](../questions.md): Q-05; 🟡 Q-07, Q-11, Q-12, Q-13.
