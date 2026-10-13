# authority-lookup — Требования

> Сценарий модуля [`canonical-model`](../requirements.md). `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Дизайн — [`design.md`](design.md).

## Обзор

`AuthorityMatrix` отвечает на вопрос «является ли источник мастером для узла / свойства / типа связи». Потребители используют ответ, чтобы решить, перезаписывает ли значение каноническое, или порождает конфликт (проектор `GraphProjector`, `PostgresSourceConflicts`, `ExplainProvenanceTool`) 🟢 (`CM/authority/AuthorityMatrix.java:20-22`; конструкторы: `IngestionServiceConfiguration.java:64, 86`, `ExplainProvenanceTool.java:28`).

## Правила

- Поиск точный и fail-closed: источник вне списка не авторитетен 🟢 (`CM/authority/AuthorityMatrix.java:17-18`).
- Тип вне матрицы (`SOURCE_SYSTEM`, `SOURCE_RECORD`, `SYNC_RUN`; связи `ASSERTS`, `OWNS_RECORD`, `PROCESSED`) → пустое множество мастеров 🟢 (`:63-65, 73-75`).
- Свойство без собственного правила наследует правило метки узла; суперметки не опрашиваются (для `VirtualMachine` берётся правило `VirtualMachine`, а не `ComputeInstance`; оба в матрице заданы отдельно) 🟢 (`:68-70`, `:85-87`).
- `MANUAL` не мастер ничего 🟢 (в `poc()` не встречается: `:77-118`; тест `AuthorityMatrixTest.dependsOnOnlyEam` проверяет `MANUAL` для `DEPENDS_ON`).
- Экземпляры неизменяемы; `defaults()` возвращает один и тот же singleton 🟢 (`:28, 43-45`).
- У каждой канонической метки ровно один мастер; у каждой бизнес-связи — ровно один; у служебных связей — ноль 🟢 (`CT/authority/AuthorityMatrixTest.java:78-87`).
- Неавторитетное расходящееся значение не заменяет каноническое и должно порождать `Conflict` — это контракт для проектора, сама матрица только отвечает «нет» 🟢 (Javadoc `CM/authority/AuthorityMatrix.java:21-22`; реализация — вне модуля 🟡).

## Таблицы матрицы (`defaults()`)

Узлы (`CM/authority/AuthorityMatrix.java:79-91`):

| Мастер | Метки |
|---|---|
| EAM | `IT_SYSTEM`, `SOLUTION`, `PLATFORM`, `TEAM` |
| SCM | `SERVICE`, `REPOSITORY` |
| CMDB | `COMPUTE_INSTANCE`, `VIRTUAL_MACHINE`, `PHYSICAL_SERVER` |
| DEPLOYMAP | `DEPLOYMENT`, `ENVIRONMENT`, `NAMESPACE`, `KUBERNETES_CLUSTER` |

Явные правила свойств (`:95-101`):

| Метка | Свойство → мастер |
|---|---|
| `IT_SYSTEM` | `criticality` → EAM; `eamCode` → EAM; `platformId` → EAM |
| `SOLUTION` | `platformId` → EAM |
| `SERVICE` | `language` → SCM |
| `REPOSITORY` | `url` → SCM |
| `COMPUTE_INSTANCE`, `VIRTUAL_MACHINE`, `PHYSICAL_SERVER` | `state` → CMDB |

Связи (`:104-115`):

| Мастер | Типы |
|---|---|
| EAM | `USES_SOLUTION`, `USES_PLATFORM`, `DEPENDS_ON`, `OWNED_BY` |
| SCM | `DECOMPOSED_INTO`, `IMPLEMENTED_IN` |
| DEPLOYMAP | `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `RUNS_ON`, `DEPLOYED_FROM`, `PART_OF` |
| CMDB | `HOSTED_ON` |

Явные правила свойств повторяют правило узла и «закрепляют» его от дрейфа (комментарий `:93`) 🟢.

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|------------|-----------|------------------|
| RF-01 | `isAuthoritative(NodeLabel, SourceSystemCode)` = источник ∈ `nodeAuthorities(label)` 🟢 (`:48-50`) | Must | `AuthorityMatrixTest.deploymentOnlyDeploymap`, `masterPerNodeLabel` |
| RF-02 | `isAuthoritative(NodeLabel, String property, SourceSystemCode)` = источник ∈ `propertyAuthorities` (правило свойства, иначе узла) 🟢 (`:53-55, 68-70`) | Must | `itSystemCriticalityOnlyEam`, `propertyWithoutOwnRuleInheritsNodeRule` |
| RF-03 | `isAuthoritative(RelationType, SourceSystemCode)` 🟢 (`:58-60`) | Must | `deployedFromBelongsToDeployMap`, `dependsOnOnlyEam`, `decomposedIntoOnlyScm` |
| RF-04 | Типы вне матрицы fail-closed 🟢 (`:63-65, 73-75`) | Must | `unknownTypesFailClosed` |
| RF-05 | У канонической метки/бизнес-связи ровно один мастер, у служебных связей нет 🟢 | Must | `everyCanonicalLabelAndDomainRelationHasExactlyOneMaster` |
| RF-06 | `platformId` у `ITSystem` и `Solution` — только EAM (ADR 0031); `eamCode` — только EAM 🟢 (`:95-96`) | Must | `solutionPlatformAndTheirRelationsAreMasteredByEam`, `platformIdOfSystemAndSolutionBelongsToEamOnly` |
| RF-07 | Возвращаемые множества неизменяемы (`Set.of`) | Should | прямого теста нет 🟡 |

## Нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Fail-closed — ни один неизвестный источник/тип не авторитетен | `CM/authority/AuthorityMatrix.java:63-75` | 🟢 |
| Потокобезопасность | Состояние неизменяемо (`Map.copyOf`, `Set.of`), singleton | `:28, 117, 120-125` | 🟢 |

## Критерии приёмки

```gherkin
Дано AuthorityMatrix.defaults()
Когда isAuthoritative(IT_SYSTEM, "criticality", EAM)
Тогда true
Когда то же для любого другого SourceSystemCode (SCM, CMDB, DEPLOYMAP, MANUAL)
Тогда false
# CT/authority/AuthorityMatrixTest.java:29

Дано AuthorityMatrix.defaults()
Когда relationAuthorities(DEPENDS_ON)
Тогда {EAM}; isAuthoritative(DEPENDS_ON, SCM) == false; isAuthoritative(DEPENDS_ON, MANUAL) == false
# CT/authority/AuthorityMatrixTest.java:37

Дано метка IT_SYSTEM и свойство description (без собственного правила)
Когда propertyAuthorities(IT_SYSTEM, "description")
Тогда {EAM} (наследуется от узла); для SCM — false
# CT/authority/AuthorityMatrixTest.java:72

Дано SOURCE_RECORD и ASSERTS (служебные)
Когда isAuthoritative(...)
Тогда false; nodeAuthorities(SOURCE_RECORD) пусто
# CT/authority/AuthorityMatrixTest.java:90

Дано COMPUTE_INSTANCE и свойство state
Когда propertyAuthorities(COMPUTE_INSTANCE, "state")
Тогда {CMDB}
# CT/authority/AuthorityMatrixTest.java:63
```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-05 | Must | Определяют каноническое значение при расхождении источников |
| RF-06 | Must | Закреплено ADR 0031 и тестами |
| RF-07 | Should | Защита от мутации, тест не нужен для работы |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `CM/authority/AuthorityMatrix.java` | вся | 🟢 |
| `CT/authority/AuthorityMatrixTest.java` | 11 тестов | 🟢 |
