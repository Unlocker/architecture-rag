# projection-identity-conflicts: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты (фикстуры, события): [`../contracts.md`](../contracts.md) §5. Спецификации под тестом: [`../../graph-projector/requirements.md`](../../graph-projector/requirements.md), [`../../identity-resolution/requirements.md`](../../identity-resolution/requirements.md), [`../../event-journal/requirements.md`](../../event-journal/requirements.md), [`../../normalizer/requirements.md`](../../normalizer/requirements.md), [`../../database/business-rules.md`](../../database/business-rules.md).

## Visão Geral

Десять классов проверяют ядро конвейера на настоящих Neo4j и PostgreSQL без Spring и без HTTP: журнал событий и статусы (`EventJournalIT`), стабильные `gid` и crosswalk (`IdentityMappingIT`), кандидаты идентичности (`IdentityCandidateIT`), конфликты источников в PostgreSQL (`PostgresSourceConflictsIT`) и в графе (`SourceConflictIT`), инвариант платформы ИТ-системы (`PlatformInvariantIT`), проекции `DEPLOYED_FROM` (`DeployedFromIT`) и срезов eam-api (`EamApiProjectionIT`), схему Neo4j (`Neo4jSchemaIT`) и семантику проектора (`GraphProjectorIT`, 51 тест). Это единственные тесты, закрывающие ловушки корректности 4 (MERGE по ключу под constraint), 5 (неполная запись не затирает), 6 (идемпотентность, версии, checkpoint после коммита), 9 (удаление только после полного snapshot), 10 (время в UTC), 14 (ошибки не глушатся) на уровне хранилищ 🟢. Классы запускаются в режиме M: `new GraphProjector / EventProcessor / PostgresEventJournal` в `@BeforeAll`.

## Regras de Negócio

- **Проекция идемпотентна.** Тот же `eventId` повторно → `PROJECTED`, граф идентичен (полный дамп узлов и связей сравнивается до и после); прямой повторный `project` с той же версией → `NOOP_SAME_VERSION`; та же версия другим `eventId` (webhook и polling) → `DUPLICATE`, а не `IGNORED_OLD_VERSION`; повтор `syncRun` не удваивает связь `PROCESSED` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:222-251, 853-859`).
- **Версии сравниваются числом:** `99` ниже `184` → `IGNORED_OLD_VERSION`, граф не меняется; доставка не по порядку (5 затем 3, 3 затем 5) сходится к 5; 16 параллельных версий одного объекта в 8 потоках оставляют один `SourceRecord` версии `16` (MERGE по ключу под constraint) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:255-299`).
- **Неполная запись не затирает:** версия 2 без `description` и `criticality` сохраняет известные значения и обновляет `name`; версия без поля `hosts` ничего не закрывает; **пустой список `hosts` неотличим от отсутствующего** и тоже ничего не закрывает (задокументированное ограничение); неизвестная `criticality` пишется в `criticalityRaw` и не затирает известную 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:303-315, 415-436`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiProjectionIT.java:250-259`). 🔴 Явный `null` в payload и семантика удаления поля тестом не закреплены (Q-34).
- **Authority:** запись не-мастера (CMDB по `Service`) → `APPLIED` с `skippedProperties = [Service.name, Service.language]`, значения мастера целы, два `ASSERTS` (`MASTER`, `SUPPLEMENTARY`); связь не-мастера (`DEPENDS_ON`) пропущена и отражена в `skippedRelations` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:319-338, 658-681`).
- **Конфликты источников (PostgreSQL):** расхождение значения не-мастера с мастером открывает один `PROPERTY`-конфликт (`OPEN`); совпадение значения — `RESOLVED`; повторное расхождение снова `OPEN` с прежним `openedAt`; результат не зависит от порядка прихода; смена значения мастера пересчитывает пару; только не-мастера конфликта не дают; отзыв утверждения (retract) мастера или диссидента закрывает конфликт, повтор не меняет строку (в т. ч. `updated_at`); конфликты других `gid` не затронуты 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java:81-209`).
- **`RELATION`-конфликты** (расхождение `eamSystemId` deploy map с системой сервиса; инвариант платформы) открываются идемпотентно, закрываются при совпадении, не открываются без известного мастера; пересчёт `PROPERTY` не трогает `RELATION`; на одном `gid` живут оба вида 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java:210-273`).
- **Конфликты в графе:** неавторитетное значение (CMDB `LOW` при мастере EAM `HIGH`) оставляет в графе `HIGH`, открывает один `OPEN`-конфликт в PG, маркер `conflicts = [criticality]` ставится **только** на ребро `ASSERTS` записи CMDB; порядок прихода не важен; совпавшее значение (новой версией CMDB или смена мастера) закрывает конфликт и снимает маркер; одинаковые значения конфликта не открывают 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:198-278`).
- **Инвариант платформы (ADR 0031):** решение раньше или позже системы с другим `platformId` открывает `RELATION`-конфликт; исправление версией системы или решения и tombstone системы → `RESOLVED`; граф строится по решению, прямого ребра «система → платформа» нет; согласованные данные конфликтов не дают 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PlatformInvariantIT.java:172-246`).
- **Темпоральные связи (ADR 0028):** `OWNED_BY` получает `validFrom` и не переоткрывается повтором версии; смена `hosts`, `ownerTeam`, сервиса и окружения, `systemCode`, `repositoryId` закрывает прежнюю связь временем события и открывает новую; вернувшийся хост переоткрывается без дубля; связь, подтверждённая другой записью, не закрывается; tombstone закрывает структурные связи; `CloseAssertion` ставит `validTo`; `TemporalRelationMigration` проставляет отсутствующий `validFrom` и идемпотентна (повтор → 0) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:342-631`).
- **Tombstone (ADR 0020):** запись `active=false`, версия растёт, узел `isCurrent=false` с `deletedAt`, связи закрыты, сосед цел; более новая версия реактивирует; узел с двумя мастер-записями живёт, пока жива вторая; delete раньше upsert блокирует старый upsert (`IGNORED_OLD_VERSION`), узел не создаётся; delete с более старой версией игнорируется 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:772-851`).
- **Сбои и карантин:** маркер `snapshot-complete` передаётся `ReconciliationTrigger` и в граф не пишется; `ServiceUnavailableException` из проекции → `RETRYING`/`GRAPH_UNAVAILABLE`, checkpoint **не сдвинут**, граф пуст, повтор → `PROJECTED` и сдвиг checkpoint; пустой payload `TEAM` → `QUARANTINED`/`MISSING_REQUIRED_FIELD`, граф пуст; неверный commit deploy map (верхний регистр) → `QUARANTINED`/`INVALID_PAYLOAD`, граф цел 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:861-922`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java:359-370`). 🟡 Сбой имитируется подменой `GraphProjection`, а не транзиентной ошибкой внутри `executeWrite` (Q-03).
- **Отложенные связи (`PendingRelation`):** зависимость до появления обоих сервисов достраивается после второго; сервис SCM до системы EAM и система до команды — аналогично; новая версия referrer заменяет отложенную; tombstone referrer удаляет pending; повторная проекция концов не дублирует ребро; повтор той же версии referrer pending не удаляет; ссылка на неизвестную цель не создаёт связь и фантомный узел 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:632-642, 943-1072`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiProjectionIT.java:157-231`).
- **Журнал событий:** `pending` отдаёт `RECEIVED`, «созревший» `RETRYING` и давно прерванный `VALIDATED` в порядке журнала keyset-ом, свежий `RETRYING` и `PROJECTED` не отдаёт; дубль `source+eventId` → `DUPLICATE` с исходной версией, строка одна, статус не меняется, тот же `eventId` из другого источника не дубль; `toDlq` → `QUARANTINED` с кодом и ссылкой на raw, повтор — одна запись; пустой код и неизвестное событие отклоняются; финальное состояние не откатывается (`PROJECTED` → `RETRYING` запрещён); checkpoint переживает «рестарт», побеждает последнее значение; raw в S3 адресуется по хешу (`raw/<source>/<hash>`), неверный хеш при чтении — ошибка; читатель журнала `JournalQuery` отдаёт страницы keyset-ом в порядке приёма с фильтрами replay-строк и окна `receivedAt`, лимит вне `1..MAX_LIMIT` отвергается 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java:108-300`).
- **Identity:** `find` пуст до `resolve`; повторный `resolve` (в т. ч. новым экземпляром) даёт тот же `gid`; один `sourceId` в EAM и SCM — разные сущности; 16 параллельных `resolve` сходятся к одному `gid`; crosswalk даёт EAM- и SCM-записи один `gid`, присоединяется к уже существующему, идемпотентен и независим от порядка; ключи с разными `gid` → `CrosswalkConflictException`, ничего не меняется; гонка `resolve`/`approve` завершается общим `gid` либо чистым откатом 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java:54-192`).
- **Кандидаты:** два CMDB-узла с одним hostname (регистр и точка нормализуются) остаются двумя узлами и порождают один `OPEN`-кандидат; имя + владелец → кандидат с двумя признаками; один владелец при разных именах и `IT_SYSTEM` против `SERVICE` с одним именем — не кандидаты; связанные crosswalk-ом записи кандидата не порождают; версия 2 с новыми `eventId` оставляет одного кандидата с прежним `firstSeenAt`; tombstone-запись не матчится с поздней; признак с числом совпадений выше предела (50) кандидатов не создаёт 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityCandidateIT.java:140-275`). 🔴 Статус кандидата остаётся `OPEN` (Q-09).
- **Схема Neo4j:** двойной `apply` создаёт каждый constraint и индекс один раз; составной UNIQUE `(source, sourceType, sourceId)` отклоняет дубль, но принимает тот же `sourceId` с другим `sourceType`; дубли `Service.gid` и `Environment.code` отклоняются; FULLTEXT `asset_text` находит `ITSystem` по имени 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:47-90`). 🟡 Отсутствие платных конструкций (NODE KEY, existence-constraints) тестом не утверждается.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Проекция идемпотентна и сравнивает версии числом | Must | AC-01 |
| RF-02 | Неполная запись не затирает известные поля | Must | AC-02 |
| RF-03 | Authority и конфликты источников в PG и в графе | Must | AC-03 |
| RF-04 | Темпоральные структурные связи: открытие, закрытие, переоткрытие | Must | AC-04 |
| RF-05 | Tombstone вместо hard delete, реактивация, защита от устаревшего upsert | Must | AC-05 |
| RF-06 | Сбой графа даёт `RETRYING` без сдвига checkpoint; негодный payload — карантин с кодом | Must | AC-06 |
| RF-07 | Отложенные связи достраиваются в любом порядке прихода | Must | AC-07 |
| RF-08 | `gid` стабилен, crosswalk без автослияния, кандидаты отдельно | Must | AC-08 |
| RF-09 | Журнал: дедупликация, переходы, DLQ, checkpoint, raw по хешу | Must | AC-09 |
| RF-10 | Схема Neo4j создаётся идемпотентно и держит ключи | Must | AC-10 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Isolamento | `@BeforeEach MATCH (n) DETACH DELETE n` (графовые классы), `+ DELETE FROM source_conflict` (`PlatformInvariantIT`, `DeployedFromIT`); `EventJournalIT`, `IdentityMappingIT`, `IdentityCandidateIT`, `PostgresSourceConflictsIT` изолируются уникальными UUID | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:113-117`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PlatformInvariantIT.java:91-97` | 🟢 |
| Tempo | Время — `Instant` UTC; в Neo4j `datetime` читается `asOffsetDateTime()`/`asZonedDateTime().toInstant()` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:350`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java:306` | 🟢 (🟡 запрета `LocalDateTime` тестом нет) |
| Concorrência | 8 потоков × 16 версий; 16 параллельных `resolve`; гонка `resolve`/`approve` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:284-299`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java:84-95, 144-192` | 🟢 |
| Cobertura | Покрыты `IGNORED_OLD_VERSION` (`GraphProjectorIT:256,269,839`, `EamApiProjectionIT:233`) и дедупликация журнала; на уровне `EventProcessor` повторная доставка того же `eventId` вызывается только в `replayDoesNotChangeGraph` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:222-239` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01: идемпотентность и версии (GraphProjectorIT.replayDoesNotChangeGraph, webhookAndPollingWithSameVersionAreDuplicateNotOldVersion, olderVersionIsIgnoredAndComparedNumerically, outOfOrderDeliveryConvergesToTheNewestVersion, concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion)
Дано объект TEAM версии 5 уже спроецирован
Когда то же событие обработано повторно либо прямой project с версией 5
Тогда статус PROJECTED либо NOOP_SAME_VERSION, дамп графа не изменился
Дано версия 184 применена
Когда приходит версия 99 другим eventId
Тогда IGNORED_OLD_VERSION, граф не изменился (сравнение числом, не строкой)
Дано 16 версий одного объекта параллельно в 8 потоках
Тогда один SourceRecord версии 16 и имя "v16"

# AC-02: неполная запись (GraphProjectorIT.partialRecordDoesNotEraseKnownFields, partialRecordWithoutHostsClosesNothing, emptyHostsListClosesNothingKnownLimitation; EamApiProjectionIT.unknownCriticalityIsStoredRawAndDoesNotOverwriteKnownValue)
Дано ITSystem с description и criticality
Когда версия 2 содержит только name
Тогда name обновлён, description и criticality прежние
Когда payload не содержит hosts либо hosts пуст
Тогда прежние связи RUNS_ON не закрываются

# AC-03: authority и конфликты (GraphProjectorIT.nonAuthoritativeSourceDoesNotReplaceCanonicalValue; SourceConflictIT.nonAuthoritativeValueOpensConflictAndMarksOnlyDissentEdge, reverseOrderGivesTheSameOutcome, agreeingValueResolvesConflictAndClearsMarker; PostgresSourceConflictsIT.*)
Дано мастер EAM criticality=HIGH, CMDB criticality=LOW
Тогда в графе HIGH, один OPEN-конфликт, маркер conflicts=[criticality] только на ребре ASSERTS записи CMDB
Когда CMDB присылает HIGH
Тогда конфликт RESOLVED, маркер снят
Дано записи пришли в обратном порядке
Тогда исход тот же
Дано только не-мастера
Тогда конфликтов нет

# AC-04: темпоральные связи (GraphProjectorIT.temporalRelationGetsValidFromAndIsNotReopenedByRepeat, replacedHostClosesOldRunsOnAtEventTimeAndKeepsNewOpen, relationReassertedByAnotherRecordIsNotClosed, temporalRelationMigrationFillsMissingValidFromAndIsIdempotent; DeployedFromIT.newCommitClosesOldDeployedFromAndOpensNewOne, sameCommitInNewVersionChangesNothing)
Дано DEPLOYED_FROM с commit A
Когда новая версия с commit B
Тогда старое ребро validTo = время события (UTC), новое открыто, sourceCommit узла = B
Когда новая версия с тем же commit
Тогда рёбра и validFrom не меняются

# AC-05: tombstone (GraphProjectorIT.tombstoneDeactivatesRecordArchivesNodeAndClosesRelations, upsertWithNewerVersionAfterTombstoneReactivates, tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains, deleteArrivingBeforeUpsertBlocksOlderUpsertAndCreatesNoNode, deleteWithOlderVersionIsIgnored)
Когда приходит delete версии 2
Тогда запись active=false, узел isCurrent=false с deletedAt, связи закрыты, сосед цел
Дано delete пришёл раньше upsert версии 1
Тогда upsert IGNORED_OLD_VERSION, узел не создан
Дано delete с версией ниже применённой
Тогда игнорируется

# AC-06: сбой и карантин (GraphProjectorIT.graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint, invalidPayloadIsQuarantinedWithCodeAndWritesNothing; DeployedFromIT.invalidCommitIsQuarantinedAndGraphIsUntouched)
Дано проекция бросает ServiceUnavailableException
Тогда RETRYING/GRAPH_UNAVAILABLE, checkpoint прежний, узла нет
Когда повторная обработка при рабочем графе
Тогда PROJECTED, checkpoint = id события
Дано payload TEAM пуст либо commit в верхнем регистре
Тогда QUARANTINED с MISSING_REQUIRED_FIELD либо INVALID_PAYLOAD и граф не изменён

# AC-07: отложенные связи (GraphProjectorIT.dependencyBeforeBothServicesAppearsAfterSecondService, scmServiceBeforeEamSystemGetsDecomposedInto, systemBeforeTeamGetsOwnedBy, tombstoneOfReferrerRemovesDeferredRelation, repeatedEndpointProjectionDoesNotDuplicateEdge; EamApiProjectionIT.verticalSliceItSystemSolutionPlatformIsBuiltInAnyOrder, unknownSystemCodeStaysDeferredAndCreatesNoPhantomNode)
Дано зависимость пришла до обоих сервисов
Тогда ребро DEPENDS_ON отсутствует, PendingRelation = 1
Когда появился второй сервис
Тогда ребро построено, pending снят
Дано неизвестный systemCode
Тогда связь отложена, фантомного узла нет

# AC-08: identity и кандидаты (IdentityMappingIT.*, IdentityCandidateIT.*)
Дано один и тот же ключ источника
Когда resolve вызван повторно, в т. ч. новым экземпляром и параллельно 16 раз
Тогда gid один
Дано два ключа с разными gid
Когда approve crosswalk
Тогда CrosswalkConflictException, mapping не изменился
Дано два CMDB-узла с одним hostname
Тогда два узла, один OPEN-кандидат (HOSTNAME)
Дано признак встречается чаще 50 раз
Тогда кандидаты не создаются

# AC-09: журнал (EventJournalIT.duplicateEventIsNotInsertedTwice, sameEventIdFromAnotherSourceIsAccepted, transitionsAndDlqArePersisted, finalStateCannotBeRolledBack, dlqRejectsBlankCodeAndUnknownEvent, readerRejectsBadLimit)
Когда append дважды с тем же source+eventId
Тогда второй результат DUPLICATE, строка одна
Когда PROJECTED переводится в RETRYING
Тогда отказ, статус прежний
Когда toDlq с пустым кодом либо для неизвестного события
Тогда отказ
Когда JournalQuery создан с limit = 0 либо MAX_LIMIT + 1
Тогда IllegalArgumentException

# AC-10: схема (Neo4jSchemaIT.*)
Когда Neo4jSchema.apply вызван дважды
Тогда набор constraint и индексов тот же
Когда создан второй SourceRecord с тем же (source, sourceType, sourceId)
Тогда нарушение уникальности; тот же sourceId с другим sourceType принят

# AC-11 (непокрыто) 🔴
Дано transient-сбой внутри executeWrite проектора либо явный null в payload
Тогда ожидаются повтор без побочных эффектов и неизменность известного поля; тестов нет (Q-03, Q-34)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-10 | Must | Критерии PoC 3 и 4, ловушки 4, 5, 6, 9, 10, 14; ADR 0003, 0008, 0019, 0020, 0028–0031 |
| Закрытие кандидата идентичности и выход из `QUARANTINED` | Won't | Не реализовано в коде (🔴 Q-09, Q-11) |
| Проверка отсутствия `LocalDateTime` | Won't | Запрета тестом нет (🟡) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java` | 51 тест | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java` | 12 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityCandidateIT.java` | 9 + 8 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PlatformInvariantIT.java` | 12 + 7 + 7 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiProjectionIT.java` | 11 + 9 | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java` | 4 | 🟢 |
