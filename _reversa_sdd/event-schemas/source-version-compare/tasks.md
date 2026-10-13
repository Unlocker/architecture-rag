# Use case: source-version-compare, Задачи

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

- [ ] T-01 `SourceVersion` с regex `[0-9]{1,40}`, `compareTo` через `BigInteger`, `isOlderThan`.
  - Origin: `ES/SourceVersion.java:10-26`
  - Критерий готовности: AC-01…AC-03.
  - Уверенность: 🟢
- [ ] TT-01 Перенести `SourceVersionTest` (2 теста).
- [ ] TT-02 Добавить AC-04 и случай ведущих нулей 🟡.

Порядок: T-01 → тесты.

## Открытые 🔴
R-05 (Q-05).
