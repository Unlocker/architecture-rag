# resolve-references — дизайн

> Общий дизайн: [`../design.md`](../design.md) §3.2, §4. `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`.

## Interface
| Символ | Сигнатура | Эффект |
|---|---|---|
| `CommandSink.relation` | `(RelationType type, SourceKey from, NodeLabel fromLabel, SourceKey to, NodeLabel toLabel, Validity validity, String field)` | перегрузка без свойств → `properties = Map.of()` (`NZ/CommandSink.java:41-50`) |
| `CommandSink.relation` | `(…, Map<String,Object> properties, Validity validity, String field)` | `NZ/CommandSink.java:53-69` |
| `CommandSink.link` | `(type, from, fromLabel, to, toLabel, Map properties, Validity validity, String fromField, String toField)` | `NZ/CommandSink.java:80-102` |
| `ReferenceResolver.exists` | `(SourceKey) → boolean` | `NZ/ReferenceResolver.java:13` |

## Поток `relation` (🟢 `NZ/CommandSink.java:62-68`)
1. `other = from.equals(self) ? to : from`.
2. `relation = new UpsertRelation(type, from, fromLabel, to, toLabel, properties, validity, self)` — может бросить `IllegalArgumentException` (недопустимая пара меток, `validity` у нетемпоральной, нет identity-свойств типа; `CM/command/UpsertRelation.java:32-40`).
3. `!resolver.exists(other)` → `unresolved.add(new UnresolvedReference(self, field, type, other, relation))`; `return`.
4. иначе `commands.add(relation)`.

Замечание: если `from != self` и `to != self` (оба чужие), `other = from`; для этого случая предназначен `link` 🟢.

## Поток `link` (🟢 `NZ/CommandSink.java:90-101`)
1. `relation = new UpsertRelation(..., assertedBy = self)`.
2. `fromKnown = exists(from)`, `toKnown = exists(to)` (оба вызова всегда выполняются).
3. `!fromKnown` → `UnresolvedReference(self, fromField, type, from, relation)`; `!toKnown` → `UnresolvedReference(self, toField, type, to, relation)`.
4. `fromKnown && toKnown` → `commands.add(relation)`.

## Алиасы
| Ссылка | Код | Evidence |
|---|---|---|
| `systemCode` (SCM) | `lookup.itSystemByEamCode(system).orElseGet(() -> LookupKeys.itSystemCode(system))` → `from` у `DECOMPOSED_INTO` | `NZ/ScmMapper.java:72-82` |
| `archrag.io/git-repo-url` (DEPLOYMAP) | `lookup.repositoryByUrl(chart.repositoryUrl()).orElseGet(() -> LookupKeys.repositoryUrl(chart.repositoryUrl()))` → `to` у `DEPLOYED_FROM` | `NZ/DeployMapMapper.java:194-207` |

Если алиас найден портом, `ReferenceResolver.exists(реальныйКлюч)` обычно истинно (см. `NT/NormalizerTest#scmServiceMapsRelationsToSystemAndRepository`). Если порт вернул ключ, а `exists` ложно — связь тоже уйдёт в `unresolved` (поведение `relation`, без особого случая) 🟡.

## Зависимости
`ReferenceResolver` (реализация `projector::isActive`, `IngestionServiceConfiguration.java:110`), `DeploymentLookup`, `UpsertRelation` (`canonical-model`), `LookupKeys`. Потребитель: `EventProcessor` превращает `unresolved` в `DeferRelation` (`EventProcessor.java:222-223`); построение отложенной связи при появлении цели — в `graph-projector` (по спецификации `docs/06-eam-integration.md`, раздел «Сопоставление ключей») 🟡 вне модуля.

## Решения
| Решение | Evidence | Уверенность |
|---|---|---|
| Отложенная ссылка несёт полную `UpsertRelation` (оба конца, свойства, `Validity`) | `NZ/UnresolvedReference.java:17` | 🟢 |
| Проверка допустимости пары меток делается в конструкторе команды, а не в `CommandSink` | `NZ/CommandSink.java:63` | 🟢 |
| `link` проверяет оба конца всегда | `NZ/CommandSink.java:91-92` | 🟢 |

## Риски
- 🟡 `ReferenceResolver` и `DeploymentLookup` вызываются внутри `try` `Normalizer.normalize`; `IllegalArgumentException` порта превратится в `INVALID_PAYLOAD` (Q-06).
- 🟡 `exists` проверяет «активную» запись; отличие «неизвестна» от «неактивна (tombstone)» — на стороне реализации порта (`projector::isActive`), в модуле не различается.
