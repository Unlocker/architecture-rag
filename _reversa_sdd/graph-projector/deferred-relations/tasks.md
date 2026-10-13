# graph-projector / deferred-relations: задачи

> Сокращения путей как в [`../requirements.md`](../requirements.md). Общие предпосылки — [`../tasks.md`](../tasks.md).

## Задачи

- [ ] **T-01. `PropertyJson`.** `write(Map)` — ключи отсортированы (`TreeMap`), значения `String/Boolean/Long/Integer/Double/Instant/List`, `Instant` как `{"$instant":"…"}`; `read(String)` — разбор с теми же типами (целые → `Long`, дробные → `Double`), «trailing characters», «must be an object», неподдерживаемое → `IllegalArgumentException`.
  Origin: `GP/PropertyJson.java:19-263`. Критерий готовности: `PropertyJsonTest` (3 теста). Уверенность: 🟢.
- [ ] **T-02. Ключ `PendingRelation`.** SHA-256 hex от конкатенации `len:part|` по десяти полям (порядок: `assertedBy.source.name`, `assertedBy.sourceType`, `assertedBy.sourceId`, `type.name`, `from.*`, `to.*`).
  Origin: `GP/PendingRelations.java:174-197`. Критерий готовности: разные наборы полей дают разный ключ, один набор — один (проверка через IT `repeatedEndpointProjectionDoesNotDuplicateEdge`). Уверенность: 🟢.
- [ ] **T-03. `store`/`delete`/`deleteOf`.**
  Origin: `GP/PendingRelations.java:42-80`. Критерий готовности: AC-01, AC-04, AC-05. Уверенность: 🟢.
- [ ] **T-04. `candidates` и `endpointGids`.** Условия `active`, `referrerVersion`; сортировка `ORDER BY p.key`; метки корней из enum.
  Origin: `GP/PendingRelations.java:86-134`. Критерий готовности: AC-02, AC-07. Уверенность: 🟢.
- [ ] **T-05. `healPending` и `healAliases`.** Порядок вызова после `UpsertNode`: сначала `healPending`, потом `healAliases`; `ALIAS_RULES` как в таблице [`design.md`](design.md).
  Origin: `GP/GraphProjector.java:88-95, 361-415`. Критерий готовности: AC-02, AC-03, AC-06. Уверенность: 🟢.
- [ ] **T-06. Ветка `DeferRelation` и замена при проекции.** `deleteOf` до команд; `store(tx, key, version.value(), eventTime, relation)`; `DeferRelation` учитывается в `closeReplacedRelations`.
  Origin: `GP/GraphProjector.java:328, 336-337, 625-626`. Критерий готовности: AC-04, AC-05, IT `unknownNewHostStillClosesOldRunsOn`. Уверенность: 🟢.
- [ ] **T-07. Сборка `DeferRelation` в оркестраторе.** `unresolved().stream().map(UnresolvedReference::relation).distinct().map(DeferRelation::new)`.
  Origin: `GP/EventProcessor.java:221-223`. Критерий готовности: `unresolvedReferenceReachesProjectorAsDeferredRelationOnce`. Уверенность: 🟢.

## Тесты

- [ ] **TT-01.** `PropertyJsonTest`, `ProjectionRequestTest#deferredRelationNeedsNoGidForUnknownEndpoint`, `#deferredRelationMustBeAssertedByRequestRecord`. Origin: `GPT/`. Уверенность: 🟢.
- [ ] **TT-02.** IT (Docker): `dependencyBeforeBothServicesAppearsAfterSecondService`, `scmServiceBeforeEamSystemGetsDecomposedInto`, `systemBeforeTeamGetsOwnedBy`, `newReferrerVersionReplacesDeferredRelation`, `tombstoneOfReferrerRemovesDeferredRelation`, `repeatedEndpointProjectionDoesNotDuplicateEdge`, `sameVersionReplayOfReferrerKeepsDeferredRelation`. Origin: `IT/GraphProjectorIT.java`. Уверенность: 🟢.
- [ ] **TT-03 (пробел).** Нет теста достраивания `REPOSITORY`-alias по `url` (правило есть в `ALIAS_RULES`, IT покрывает только alias `eamCode`) и теста «неавторитетная достроенная связь» с удалением `PendingRelation`. Origin: `GP/GraphProjector.java:94, 372-373`. Уверенность: 🟡.

## Порядок

T-01 → T-02 → T-03 → T-04 → T-06 → T-05 → T-07.

## Открытые вопросы

[`../questions.md`](../questions.md): Q-17, Q-18 (🟡), Q-08 (🔴, формулировка `docs/02-data-model.md:273`).
