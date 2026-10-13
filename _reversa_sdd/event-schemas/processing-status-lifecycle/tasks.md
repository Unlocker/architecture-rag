# Use case: processing-status-lifecycle, Задачи

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

- [ ] T-01 Enum `ProcessingStatus` в порядке RECEIVED, VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, RETRYING, SUPERSEDED, DUPLICATE, IGNORED_OLD_VERSION.
  - Origin: `ES/ProcessingStatus.java:8-18`
  - Критерий готовности: `values()` возвращает 10 значений в этом порядке.
  - Уверенность: 🟢
- [ ] T-02 `canTransitionTo` по таблице модуля.
  - Origin: `ES/ProcessingStatus.java:26-42`
  - Критерий готовности: AC-01…AC-05.
  - Уверенность: 🟢
- [ ] TT-01 Перенести 5 тестов `ProcessingStatusTest`.
- [ ] TT-02 Добавить полную матрицу 10×10 и AC-06 🟡.

Порядок: T-01 → T-02 → тесты.

## Открытые 🔴
R-02, R-03 (Q-01, Q-02 в [questions.md](../questions.md)).
