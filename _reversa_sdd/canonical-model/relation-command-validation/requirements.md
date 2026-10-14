# relation-command-validation — Требования

> Сценарий модуля [`canonical-model`](../requirements.md). `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Проектирование — [`design.md`](design.md).

## Обзор

Создание команд `UpsertRelation`, `DeferRelation` проверяет структурную допустимость связи (пара меток по `RelationType`), темпоральность и свойства. Это единственный барьер перед проектором: нормализатор, нарушивший инвариант, получает исключение, которое он превращает в `INVALID_PAYLOAD` 🟡 (`ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/Normalizer.java:80` — перехват `IllegalArgumentException`; прочие вызовы `catch (IllegalArgumentException)` в мапперах).

## Правила

- Пара меток допустима, если `from` — подтип одного из `sources` и `to` — подтип одного из `targets` 🟢 (`CM/relation/RelationType.java:73-75`).
- `validity` допустим только для темпоральных типов; `null` допустим всегда 🟢 (`CM/command/UpsertRelation.java:35-37`).
- `properties` не `null`; ключи — непустой текст; значения — `String|Boolean|Long|Integer|Double(конечный)|Instant|List<те же, без вложенности>`; результат неизменяем, не зависит от входной карты 🟢 (`CM/Invariants.java:49-77`).
- Все `identityProperties` типа присутствуют среди ключей `properties` 🟢 (`CM/command/UpsertRelation.java:39-41`); сейчас это только `commit` у `DEPLOYED_FROM` (`CM/relation/RelationType.java:20`).
- Имена свойств `DEPENDS_ON` (`kind`/`protocol`/`criticality`) здесь не проверяются 🟢 (`CM/command/UpsertRelation.java:18-19`).
- Значения `Validity` — `validFrom ≤ validTo` (равенство допустимо), `validTo == null` — открыта 🟢 (`CM/relation/Validity.java:10-15`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|------------|-----------|------------------|
| RF-01 | Недопустимая пара меток → `IllegalArgumentException("<TYPE> does not allow <FROM> -> <TO>")` 🟢 | Must | `GraphCommandTest.disallowedLabelsRejected` |
| RF-02 | `validity != null` для нетемпорального типа → IAE `"validity is allowed only for temporal relation <TYPE>"` 🟢 | Must | `GraphCommandTest.validityOnlyForTemporal` |
| RF-03 | Некорректное значение свойства (`NaN`, `±∞`, неподдерживаемый тип, вложенный список) → IAE 🟢 | Must | `GraphCommandTest.nonFiniteDoublesRejected`, `propertiesValidatedAndImmutable` |
| RF-04 | Свойства копируются неизменяемо: последующее изменение исходной карты/списка не влияет на команду; `put` в `properties()` → `UnsupportedOperationException` 🟢 | Must | `GraphCommandTest.propertiesValidatedAndImmutable` |
| RF-05 | Нехватка identity-свойства → IAE `"<TYPE> requires identity properties [<names>]"` 🟢 | Must | прямого теста нет 🔴 (сам набор покрыт `RelationTypeTest.deployedFromIsTemporalAndIdentifiedByCommit`) |
| RF-06 | `null` в обязательных полях (`type, from, fromLabel, to, toLabel, assertedBy`, а также `properties`) → NPE 🟢 | Must | не покрыт для `UpsertRelation` напрямую 🟡 |
| RF-08 | `DeferRelation(null)` → NPE; `DeferRelation` оборачивает уже валидный `UpsertRelation` 🟢 | Should | нет теста конструктора 🔴 |
| RF-09 | `RelationType.allows` учитывает подтипы (RUNS_ON принимает `VIRTUAL_MACHINE`, `PHYSICAL_SERVER`, `COMPUTE_INSTANCE`, `NAMESPACE`; `HOSTED_ON` не принимает `COMPUTE_INSTANCE` как источник) 🟢 | Must | `RelationTypeTest.runsOnTargets`, `hostedOnOnlyVmToPhysical` |

## Нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Неизменяемость | `properties` — `Map.copyOf`, списки — `List.copyOf` | `CM/Invariants.java:55-57` | 🟢 |

## Критерии приёмки

```gherkin
Дано RelationType.RUNS_ON и Validity(T, null)
Когда UpsertRelation(RUNS_ON, from=DEPLOYMENT, to=VIRTUAL_MACHINE, Map.of(), validity, assertedBy)
Тогда команда создана
# CT/command/GraphCommandTest.java:34

Дано RelationType.PART_OF (нетемпоральный)
Когда UpsertRelation(PART_OF, NAMESPACE, KUBERNETES_CLUSTER, Map.of(), Validity(T,null), ...)
Тогда IllegalArgumentException
# CT/command/GraphCommandTest.java:34

Дано RelationType.RUNS_ON
Когда UpsertRelation(RUNS_ON, DEPLOYMENT, ENVIRONMENT, ...)
Тогда IllegalArgumentException (ENVIRONMENT не в targets)
# CT/command/GraphCommandTest.java:43

Дано properties {weight: NaN} (или List.of(NaN), ±Infinity)
Когда UpsertRelation(DEPENDS_ON, SERVICE, SERVICE, props, null, ...)
Тогда IllegalArgumentException
# CT/command/GraphCommandTest.java:49

Дано HashMap props и ArrayList tags, изменяемые после создания
Когда команда создана, затем props.put("later","x"), tags.add("b")
Тогда cmd.properties() содержит только исходные ключи, tags == List.of("a"); cmd.properties().put → UnsupportedOperationException
# CT/command/GraphCommandTest.java:59

Дано RelationType.DEPLOYED_FROM
Когда UpsertRelation(DEPLOYED_FROM, ..., Map.of(), ...)   # без "commit"
Тогда IllegalArgumentException "DEPLOYED_FROM requires identity properties [commit]"
# теста нет 🔴 (TT-05 в tasks.md)

```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-04, RF-09 | Must | Каждая связь проходит через `UpsertRelation` (`ingestion/normalizer/.../CommandSink.java`) |
| RF-05, RF-06 | Must | Защита идентичности `DEPLOYED_FROM` (ADR 0029) |
| RF-08 | Should | Единственный производитель — `EventProcessor.java:223` |

## Трассировка

| Файл | Класс | Покрытие |
|---|---|---|
| `CM/command/UpsertRelation.java` | `UpsertRelation` | 🟢 |
| `CM/command/DeferRelation.java` | `DeferRelation` | 🟢 |
| `CM/relation/RelationType.java`, `Validity.java` | допустимость связи | 🟢 |
| `CM/Invariants.java` | `copyProperties` | 🟢 |
