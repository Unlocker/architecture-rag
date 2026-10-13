# authority-lookup — Задачи

Общий дизайн — [`../design.md`](../design.md). `CM/` и `CT/` — как в [`../requirements.md`](../requirements.md).

## Пререквизиты
- [ ] `NodeLabel`, `RelationType`, `SourceSystemCode` реализованы.

## Задачи

- [ ] T-01 — Неизменяемый `AuthorityMatrix` с тремя картами (узлы, свойства, связи) и приватным конструктором.
  - Origin: `CM/authority/AuthorityMatrix.java:26-40`
  - Критерий готовности: нет публичных конструкторов, `defaults()` возвращает один и тот же объект.
  - Уверенность: 🟢
- [ ] T-02 — Методы `nodeAuthorities`, `propertyAuthorities` (fallback на узел), `relationAuthorities` (fail-closed `Set.of()`).
  - Origin: `CM/authority/AuthorityMatrix.java:63-75`
  - Критерий готовности: неизвестные ключи дают пустое множество; суперметка не опрашивается.
  - Уверенность: 🟢
- [ ] T-03 — Три перегрузки `isAuthoritative`.
  - Origin: `CM/authority/AuthorityMatrix.java:48-60`
  - Критерий готовности: `contains(source)` по соответствующему множеству.
  - Уверенность: 🟢
- [ ] T-04 — `poc()`: заполнить таблицы из [`requirements.md`](requirements.md) (13 меток, 9 правил свойств, 12 связей) без `MANUAL`.
  - Origin: `CM/authority/AuthorityMatrix.java:77-118`
  - Критерий готовности: `AuthorityMatrixTest.*` зелёные.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 — `itSystemCriticalityOnlyEam`, `dependsOnOnlyEam`, `decomposedIntoOnlyScm`, `deployedFromBelongsToDeployMap`, `deploymentOnlyDeploymap`: есть.
- [ ] TT-02 — `propertyWithoutOwnRuleInheritsNodeRule`, `masterPerNodeLabel`: есть.
- [ ] TT-03 — `unknownTypesFailClosed`, `everyCanonicalLabelAndDomainRelationHasExactlyOneMaster`: есть.
- [ ] TT-04 — `solutionPlatformAndTheirRelationsAreMasteredByEam`, `platformIdOfSystemAndSolutionBelongsToEamOnly`: есть.
- [ ] TT-05 — возвращаемые множества нельзя изменить: отсутствует 🟡.

## Порядок
T-01 → T-02 → T-03 → T-04.

## Открытые 🔴
- Нужна ли конфигурируемая матрица (сейчас три `defaults()` и жёсткая таблица)? — [`../questions.md`](../questions.md).
- Синхронизировать `docs/02-data-model.md:170` и ADR 0008 с кодом.
