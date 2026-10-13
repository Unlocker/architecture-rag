# Домен: глоссарий и бизнес-правила

> Detective, этап R.4 (UNLOCKER-269). Источник — код ветки `epic/UNLOCKER-260` (после R.3) и Git-история. Решения, уже закреплённые в `docs/adr/0001–0031`, здесь не пересказываются: даётся ссылка и то, **как правило реализовано в коде** (с `файл:строка`). Расхождения кода с ADR или `docs/` — 🔴/🟡, сводка в [`detective-gaps.md`](detective-gaps.md). Шкала: 🟢 прочитано в коде · 🟡 выведено · 🔴 пробел или расхождение. Состояния — [`state-machines.md`](state-machines.md), права — [`permissions.md`](permissions.md), карта ADR — [`adrs/retroactive-map.md`](adrs/retroactive-map.md), кандидаты в ADR — [`adrs/candidates.md`](adrs/candidates.md).

## 1. Глоссарий

| Термин | Значение в коде | Источник |
|---|---|---|
| `SourceKey` | ключ записи источника `(source, sourceType, sourceId)`; `source` — `SourceSystemCode` (`EAM`, `SCM`, `CMDB`, `DEPLOYMAP`, `MANUAL`) | ADR 0003; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:633-645` 🟢 |
| `gid` | UUID канонического узла; выдаётся один раз и навсегда только `IdentityMapping` | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityMapping.java:8-14` 🟢 |
| `SourceRecord` | узел «запись источника» с версией, hash, `active`; утверждает (`ASSERTS`) канонический узел | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:447-462` 🟢 |
| MASTER / SUPPLEMENTARY | значение `authority` на ребре `ASSERTS`: источник — мастер метки узла или нет | `GraphProjector.java:422, 440-441` 🟢 |
| Tombstone | пометка `SourceRecord.active=false` + `deletedAt`; физического удаления нет | ADR 0020; `GraphProjector.java:507-518` 🟢 |
| Missing set | активные записи источника, которых нет в завершённом snapshot | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/Reconciler.java:28-31, 119-142` 🟢 |
| Crosswalk | подтверждённое оператором соответствие двух `SourceKey` одной сущности | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/Crosswalk.java:9-14` 🟢 |
| Кандидат (`identity_candidate`) | подозрение «две записи — один объект»; только запись, узлы не объединяются | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityCandidate.java:9-14` 🟢 |
| Конфликт (`source_conflict`) | расхождение значения неавторитетной записи с авторитетной | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/SourceConflict.java:8-23` 🟢 |
| Дельта / snapshot | инкрементальное чтение по курсору и полное чтение с маркером `snapshot-complete` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java:19-31` 🟢 |
| `GraphCommand` | единственный вход проектора: `UpsertNode`, `UpsertRelation`, `DeferRelation`, `TombstoneSourceRecord`, `CloseAssertion` | `EventProcessor.java:568-586` 🟢 |

---

## 2. Authority matrix: кто чем владеет

Правило (ADR 0008): мастер задаётся для типа узла, для свойства и для типа связи; поиск **точный и fail-closed** — источник вне списка не авторитетен, тип вне матрицы имеет пустое множество мастеров, свойство без своего правила наследует правило метки, суперметки не просматриваются, `MANUAL` авторитетным не бывает (`contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/authority/AuthorityMatrix.java:14-22, 64-74`) 🟢.

### 2.1. Узлы (`AuthorityMatrix.java:79-91`)

| Мастер | Метки |
|---|---|
| `EAM` | `ITSystem`, `Solution`, `Platform`, `Team` |
| `SCM` | `Service`, `Repository` |
| `CMDB` | `ComputeInstance`, `VirtualMachine`, `PhysicalServer` |
| `DEPLOYMAP` | `Deployment`, `Environment`, `Namespace`, `KubernetesCluster` |

### 2.2. Свойства с собственным правилом (`AuthorityMatrix.java:95-101`)

| Метка | Свойства | Мастер |
|---|---|---|
| `ITSystem` | `criticality`, `eamCode`, `platformId` | EAM |
| `Solution` | `platformId` | EAM |
| `Service` | `language` | SCM |
| `Repository` | `url` | SCM |
| `ComputeInstance`, `VirtualMachine`, `PhysicalServer` | `state` | CMDB |

Эти правила повторяют правило узла и «закрепляют его от дрейфа» (комментарий `AuthorityMatrix.java:93-94`); для `platformId` это явное требование ADR 0031 и теста `AuthorityMatrixTest.platformIdOfSystemAndSolutionBelongsToEamOnly` (ADR 0031, «Ограничения», п. 4) 🟢.

### 2.3. Связи (`AuthorityMatrix.java:104-115`)

| Мастер | Типы связей |
|---|---|
| `EAM` | `USES_SOLUTION`, `USES_PLATFORM`, `DEPENDS_ON`, `OWNED_BY` |
| `SCM` | `DECOMPOSED_INTO`, `IMPLEMENTED_IN` |
| `DEPLOYMAP` | `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `RUNS_ON`, `DEPLOYED_FROM`, `PART_OF` |
| `CMDB` | `HOSTED_ON` |

`ASSERTS`, `OWNS_RECORD`, `PROCESSED` в матрице отсутствуют: их пишет сам проектор (`AuthorityMatrix.java:14-18`) 🟢.

### 2.4. Как матрица применяется (поведение, не декларация)

1. **Свойства.** Проектор пишет свойство узла, только если источник события авторитетен для `(label, property)`; остальные пропускает и возвращает в `ProjectionResult.skippedProperties` (`GraphProjector.java:422-432`) 🟢. `null` в свойства не попадает (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/NodeProperties.java:16, 34`): неполная запись не затирает известное (отдельной семантики «удалить поле» у источника нет) 🟢.
2. **Связи.** Связь пишется, только если *утверждающая запись* (`assertedBy`) авторитетна для её типа; иначе пропускается и попадает в `skippedRelations` (`GraphProjector.java:559-563`) 🟢. Утверждающая запись — сама запись события (`CommandSink.relation`, `self`) (`ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/CommandSink.java:62-63`) 🟢. Пример: `DECOMPOSED_INTO` идёт от `ITSystem` к `Service`, но утверждает её запись `Service` из SCM (`ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/ScmMapper.java:68-82`) 🟢.
3. **Узел.** `MASTER` у ребра `ASSERTS` — результат `isAuthoritative(label, source)`; неавторитетная запись получает `SUPPLEMENTARY` (`GraphProjector.java:422, 441`) и не удерживает узел живым и не воскрешает его (см. [`state-machines.md`](state-machines.md), §6) 🟢.
4. **Расхождение значений.** Неавторитетное значение с другим текстом порождает `source_conflict`, но не заменяет canonical-значение (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:44-47`) 🟢. Сравниваются *утверждения всех записей `gid`* в PostgreSQL, а не состояние графа, поэтому результат не зависит от порядка прихода мастера и неавторитетного источника (Javadoc `SourceConflicts.java:10-18`) 🟢.
5. **Конфликты связей** (`kind = RELATION`) — отдельные проверки двух инвариантов, см. §6.
6. 🟡 Неавторитетный источник *первым* создаёт «пустой» текущий узел (все его свойства пропущены) — см. [`state-machines.md`](state-machines.md), §6.
7. 🟡 `MANUAL` в `SourceSystemCode` есть, но решения «вручную» не реализованы (ручное разрешение конфликта отложено в E3, комментарий `AuthorityMatrix.java:14-22`); оператор может влиять на систему только через crosswalk.

---

## 3. Identity resolution

Политика трёх ступеней (ADR 0019): deterministic mapping → approved crosswalk → candidate matching; **автоматического merge нет** ни при каком score (`EventProcessor.java:64-67`).

### 3.1. Deterministic mapping

- `gid` для `SourceKey` создаётся один раз: `INSERT ... ON CONFLICT DO NOTHING` + чтение, гонка сходится к одному значению; rebuild и повтор возвращают тот же `gid` (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:20-28, 46`; `IdentityMapping.java:11-13`) 🟢.
- `resolve` вызывается **до** транзакции Neo4j и только для ключа самой записи (`EventProcessor.java:587-588`); `gid` чужого конца связи не создаётся: нет mapping — `ProjectionException(UNRESOLVED_ENDPOINT)` → `RETRYING` (`EventProcessor.java:589-597`) 🟢. Это защита от фиктивных узлов.
- Формат `sourceId` — ключ источника без префикса системы (ADR 0024). Системе EAM ключ `ITSystem` — целочисленный `id`; `eam_id` — свойство `eamCode`, а не ключ; на ключ `(EAM, IT_SYSTEM, <systemCode>)` ссылаться нельзя, он живёт как alias (`ScmMapper.java:68-71`; `GraphProjector.java:92-95`) 🟢.

### 3.2. Approved crosswalk

- Оператор загружает пары через `POST /admin/crosswalks` (1…1000 элементов); каждый элемент — отдельная запись аудита и отдельный исход `APPLIED`/`CONFLICT`/`INVALID` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:31, 117-123`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:35-64`) 🟢.
- `approve`: оба ключа получают общий `gid` (существующий у одного из них или новый); повтор идемпотентен; если ключи уже отображены на **разные** `gid` — `CrosswalkConflictException` (`PostgresIdentityMapping.java:81, 120-122`; `IdentityMapping.java:26-33`) 🟢. Слияние двух существующих `gid` не выполняется (`CrosswalkService.java:16-20`).
- Аудит утверждения обязателен: `approvedBy` (= `sub` токена), `reason` (в аудит усекается до 200 символов), `approvedAt` (`Crosswalk.java:14-25`; `CrosswalkService.java:48, 70-78`) 🟢.
- 🟡 Узлы графа под новый `gid` не переписываются сразу: их приведёт следующий replay или rebuild (`CrosswalkService.java:18-19`). Между `approve` и replay канонический граф и `identity_mapping` расходятся.

### 3.3. Candidate matching

- Признаки и веса зашиты в коде: `HOSTNAME 0.6` (сильный), `REPOSITORY_URL 0.8` (сильный), `NAME 0.4` (сильный), `OWNER 0.2` (слабый); `score = min(1.0, сумма весов различных совпавших видов)`, шкала 2; `OWNER` только повышает score и сам кандидата не создаёт (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureKind.java:8-47`) 🟢.
- Признаки узла события (`FeatureExtractor.extract`): `ComputeInstance` → `HOSTNAME`; `Repository` → `REPOSITORY_URL`; `ITSystem`, `Service`, `Team` → `NAME`; `OWNER` — `gid` команды по `OWNED_BY` (только для `ITSystem` и `Service`) (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureExtractor.java:43-69`) 🟢. Значения нормализуются (`FeatureNormalizer`), сырое значение источника не хранится (`Feature.java:5`) 🟢.
- Сравнение идёт внутри семейства меток: подтипы `ComputeInstance` — одно семейство (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/LabelFamily.java:5-12`) 🟢.
- Пары, уже делящие `gid` (через crosswalk), не порождают кандидатов (`PostgresIdentityCandidates.java:43-50`) 🟢.
- **Защита от взрыва:** если у одного признака больше `MAX_MATCHES = 50` совпадений, кандидаты по нему не создаются (WARN с числом, без значения) (`PostgresIdentityCandidates.java:33, 205-216`) 🟡: в такой ситуации пары *молча* теряются, а `matched` при upsert перезаписывается без пропущенного признака (допущение PoC в Javadoc, `PostgresIdentityCandidates.java:21-29`).
- **Конкурентность:** параллельные `record` с одним значением признака сериализует `pg_advisory_xact_lock(hashtextextended(...))` на `(семейство, вид, значение)`; сначала блокируется ключ записи, затем значения в детерминированном порядке — взаимной блокировки нет (`PostgresIdentityCandidates.java:37, 78-102, 154-169`) 🟢.
- Пара хранится в нормализованном порядке `left < right` (лексикографически по `(source, sourceType, sourceId)`), его держит только приложение, не БД (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/SourceKeys.java:5-16`; `_reversa_sdd/database/business-rules.md`, D8) 🟢.
- Tombstone записи удаляет её признаки (`forget`), но не уже созданных кандидатов (`EventProcessor.java:270-274`; [`state-machines.md`](state-machines.md), §3) 🟡.
- 🔴 Жизненный цикл кандидата не завершён: статус всегда `OPEN` (подробно — [`state-machines.md`](state-machines.md), §3).

### 3.4. Порядок побочных эффектов

В `upsert` вне транзакции Neo4j и **до** неё выполняются: `resolveGids` → `recordCandidates` (PostgreSQL) → `ignoreIfOlderOnRetry` → `assertProperties`/`assertSystemRelation`/`assertSystemPlatform` (PostgreSQL) → только затем `projection.project` (`EventProcessor.java:225-251, 346-358`) 🟢. Внутри `executeWrite` нет записей в PostgreSQL/S3: функция транзакции повторяется драйвером при транзиентных ошибках, и побочные эффекты вне Neo4j повторились бы (`GraphProjector.java:38-41`) 🟢. Цена: после падения между PostgreSQL-шагами и записью в граф повторное событие заново заменяет признаки и утверждения (операции идемпотентны, `EventProcessor.java:234, 247`).

---

## 4. Идемпотентность и версии

| Правило | Реализация | Источник |
|---|---|---|
| Дедуп по `source + eventId` | `UNIQUE (source, event_id)`, `ON CONFLICT DO NOTHING` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:56-59` 🟢 |
| Версия — число, не строка | `SourceVersion`: только цифры (1…40), сравнение `BigInteger`; нечисловая версия отклоняется валидацией | `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SourceVersion.java:12-21` 🟢 |
| Выше → применить | `VersionDecision.APPLY` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/VersionDecision.java:20-26` 🟢 |
| Равна → повтор (не ошибка) | `SAME` → статус `DUPLICATE` (webhook и polling дают разные `eventId` одного изменения) | `VersionDecision.java:5-13`; `EventProcessor.java:317-321` 🟢 |
| Ниже → пропустить | `OLD` → `IGNORED_OLD_VERSION`, граф не меняется | `EventProcessor.java:317-321` 🟢 |
| Tombstone с равной версией | применяется к ещё активной записи (удаление не обязано повышать версию) | `VersionDecision.java:29-38` 🟢 |
| Гонка двух событий одного объекта | сравнение версии в *той же* транзакции после блокировки записи (`SET r._lock`) | `GraphProjector.java:304-324` 🟢 |
| Повтор пережил новую версию | `RETRYING`-событие перепроверяется до записи в PostgreSQL | `EventProcessor.java:324-344` 🟢 |
| `eventId` конструкции | polling `poll:<type>/<id>/v<ver>`, snapshot `snap:<run>:<type>/<id>/v<ver>`, маркер `snapshot-complete:<run>`, replay `replay:<id>:<eventId>`, reconcile `reconcile:<run>:<type>/<id>` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java:99, 104`; `ReplayService.java:88-89`; `Reconciler.java:158` 🟢 |
| Checkpoint только после коммита | `finish` → `saveCheckpoint("projector", source, eventId)` после перехода в конечный статус; при `RETRYING`/`QUARANTINED` не сдвигается | `EventProcessor.java:612-617` 🟢 |
| Курсор poller'а | сдвигается после `append` всей страницы **в журнал** (не после проекции) | `Poller.java:22-25, 136` 🟢 / см. 🟡 в [`detective-gaps.md`](detective-gaps.md), П-7 |

Для каждого объекта `(source, sourceType, sourceId)` порядок обработки сохраняется: диспетчер пропускает более поздние события объекта, если предыдущее ушло в `RETRYING` или упало (`JournalDispatcher.java:111-121`) 🟢.

---

## 5. Tombstone и удаления

1. **Источник сказал «удалено»** (`architecture.asset.deleted.v1`): событие минует нормализатор; обязательна схема `urn:corp:schema:asset-deleted:1`, иначе `QUARANTINED/UNKNOWN_SCHEMA_VERSION`; признаки кандидатов снимаются, утверждения конфликтов снимаются (`retract`), в граф идёт `TombstoneSourceRecord` (`EventProcessor.java:254-287`) 🟢.
2. **Источник не отдал объект в полном snapshot:** `Reconciler` по маркеру `snapshot-complete` помечает tombstone только активные записи, которые (а) не вошли в прогон по журналу и (б) не затронуты никаким событием с начала прогона. Если событий прогона в журнале меньше `objectCount` маркера, бросается `IllegalStateException`, ничего не удаляется, маркер уйдёт в `RETRYING` (`Reconciler.java:100-142`; `EventProcessor.java:293-298`) 🟢.
3. **Tombstone `Reconciler` пишется в журнал до записи в граф** событием `reconcile:<run>:<type>/<id>` с raw в S3, чтобы replay и rebuild воспроизводили удаления; повтор доводит строку до конечного статуса (`Reconciler.java:45-48, 156-194`) 🟢. В ADR этого нет — [`adrs/candidates.md`](adrs/candidates.md), К-5.
4. **Tombstone с версией записи:** `Reconciler` берёт уже применённую версию; если объект успели обновить, проекция вернёт `IGNORED_OLD_VERSION` и новое состояние не удалится (`Reconciler.java:42-43, 130-135`) 🟢.
5. **Канонический узел** архивируется, только если нет других активных MASTER-утверждений (`GraphProjector.java:543-548`), см. [`state-machines.md`](state-machines.md), §6. Рёбра закрываются через `validTo` (ADR 0028).
6. **Retention.** `inbox_event`, `dlq_entry`, `admin_audit`, S3 raw не чистятся — сознательный выбор PoC (`_reversa_sdd/database/business-rules.md`, §3) 🟡; политики архивации нет (ADR 0020, «Последствия»).
7. 🟡 Ручной запуск `POST /admin/reconcile/{source}` не повторяет «missing set», а запускает *новый полный snapshot* в adapter-сервисе (`AdminController.java:94-115`; коммит `9656ce5`), поэтому «безопасность удаления» зависит от полноты нового snapshot, а не от какого-то сохранённого списка.

---

## 6. Инварианты предметной области

### 6.1. Структурные (контракты `canonical-model`)

- Допустимые концы и тип связи задаёт `RelationType`: например, `RUNS_ON: Deployment → ComputeInstance | Namespace`, `OWNED_BY: ITSystem | Service → Team` (`contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/relation/RelationType.java:13-27, 72-75`) 🟢. Нарушение → `IllegalArgumentException` → `QUARANTINED/INVALID_COMMANDS` (`EventProcessor.java:361-362`) 🟢.
- Время — `Instant`, в Neo4j `datetime` UTC (`NodeProperties.utc`); `firstSeenAt` только уменьшается, `lastSeenAt` только растёт (`GraphProjector.java:458-459`) 🟢; `firstSeenAt ≤ lastSeenAt`, `isCurrent == (deletedAt == null)` держит код, не БД (`_reversa_sdd/database/business-rules.md`, §3).
- Значения свойств: `String`, `Boolean`, `Long`, `Integer`, конечный `Double`, `Instant` и их списки; `null` и `NaN` отклоняются (`contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/Invariants.java:49-78`) 🟢.
- Метки подтипов, типы связей и идентичные свойства связи подставляются в Cypher только из `enum` (`GraphProjector.java:78-79, 442-446`) 🟢.

### 6.2. Иерархия EAM и инвариант IT System ↔ Platform (ADR 0031)

- Строгая иерархия `Platform → Solution → ITSystem`: `Solution.platformId` даёт `USES_PLATFORM`, `ITSystem` ссылается на решение через `product_id` (`USES_SOLUTION`) и **не** имеет прямой связи с платформой (`EamMapper.java:149-181`; комментарий `EamMapper.java:180`) 🟢.
- `ITSystem.platformId` хранится как свойство только для проверки: если оно расходится с `platformId` решения системы, открывается конфликт `RELATION` `property='platformId'`; граф не исправляется (`EventProcessor.java:494-512`) 🟢.
- Проверка идёт в двух точках, чтобы порядок прихода не имел значения: (a) событие `IT_SYSTEM` до записи графа (ключ решения — из `USES_SOLUTION` этого же события, включая отложенную) и (b) событие `SOLUTION` *после* успешной записи графа, не на `IGNORED_OLD_VERSION` и не на tombstone (`EventProcessor.java:494-561, 374-382`) 🟢.
- Без `platformId` у системы, без известного решения или без его `platformId` — master пуст, открытые строки закрываются (`EventProcessor.java:494-512`) 🟢.
- `null` у решения не затирает известную платформу: значение берётся из графа (`EventProcessor.java:539-546`) 🟢.
- Сбой хранилища конфликтов — `RETRYING/SOURCE_CONFLICT_FAILED`, повтор безопасен (`EventProcessor.java:246-249, 378-381`) 🟢.
- Ограничение (ADR 0031, п. 1–2): маркер `ASSERTS.conflicts` системы при проверке (b) не пишется, он обновится со следующим событием системы; tombstone решения и поздний приход платформы перепроверку не запускают 🟡.

### 6.3. Инвариант «система EAM развёртывания ↔ система сервиса» (ADR 0030)

- `Deployment.eamSystemId` из Helm chart сверяется с системой EAM *сервиса*, к которому развёртывание привязано (`HAS_DEPLOYMENT` этого же события, система сервиса — из графа: активная `ITSystem` с открытой `DECOMPOSED_INTO` на сервис). Больше одной системы у сервиса — WARN и конфликт не открывается (`EventProcessor.java:422-453`; `GraphProjector.java:224-230`) 🟢.
- Событие SCM-сервиса перепроверяет его развёртывания с `eamSystemId` (`recheckServiceDeployments`), tombstone развёртывания снимает утверждение (`EventProcessor.java:461-484, 563-566`) 🟢.
- Без `eamSystemId` проверки нет, прежний открытый конфликт закрывается (`EventProcessor.java:445-449`) 🟢.
- Авто-исправления нет (ADR 0019): привязка `HAS_DEPLOYMENT`/`DEPLOYED_FROM` остаётся как есть 🟢.

### 6.4. Развёртывание привязано к репозиторию и commit (ADR 0029)

- `DEPLOYED_FROM {commit}` темпоральна, `commit` входит в идентичность связи (`RelationType.java:20`; `GraphProjector.java:69-71, 580-584`) 🟢.
- Alias-ключи разрешают ссылки «по URL репозитория» и «по коду системы»: `SourceKey(SCM, REPOSITORY_URL, url)` достраивается записью SCM `REPOSITORY` с тем же `url` (URL нормализуется `GitUrls`), `SourceKey(EAM, IT_SYSTEM_CODE, code)` — записью EAM `ITSystem` с тем же `eamCode` (`GraphProjector.java:73-76, 92-95`; `ScmMapper.java:99-112`) 🟢.

### 6.5. Валидация и карантин на входе

| Условие | Код | Куда |
|---|---|---|
| `source` не `urn:corp:{eam,scm,cmdb,deploymap}` | `UNKNOWN_SOURCE` | `QUARANTINED` (`EventProcessor.java:203-205, 633-645`) |
| тип события не в `{asset.upserted, asset.deleted, sync.snapshot-complete}` | `UNSUPPORTED_EVENT_TYPE` | `QUARANTINED` (`EventProcessor.java:193-198`) |
| неизвестная версия `dataschema` | `UNKNOWN_SCHEMA_VERSION` | `QUARANTINED` (`Normalizer.java:65`; `EventProcessor.java:259-261`) |
| нет обязательного поля / неизвестное значение словаря / нарушение инвариантов модели | `MISSING_REQUIRED_FIELD`, `INVALID_PAYLOAD`, `UNKNOWN_SOURCE_TYPE` | `QUARANTINED` (`Normalizer.java:79-83`; `EamMapper.java:124`; `ScmMapper.java:95`) |
| неизвестное значение `criticality` | не ошибка: `criticality = null`, сырое значение в `unmappedCriticality`, предупреждение `UNMAPPED_CRITICALITY` | запись применяется (`EamMapper.java:161-179`) |
| нечисловая `sourceVersion` | `IllegalArgumentException` при построении `SourceVersion` | отклоняется до сравнения (`SourceVersion.java:13-15`) |
| недоверенный текст источника | не интерпретируется как инструкция; в логи и причины не попадает целиком: причины фиксированные, `toString` конфликта без значений | `EventProcessor.java:234, 247`; `SourceConflict.java:77-81` 🟢 |

`PARTIAL`-полнота событием не учитывается нормализатором: «отсутствующее поле» равно «поле не передано» (оно просто не пишется), а не «поле удалено» (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/NodeProperties.java:16`); явной семантики удаления поля нет — то есть неполная запись не затирает известное 🟢. 🟡 Различие `COMPLETE`/`PARTIAL` влияет только на замещение темпоральных связей (см. [`state-machines.md`](state-machines.md), §7).

---

## 7. Темпоральность

| Что | Правило | Источник |
|---|---|---|
| Какие связи темпоральны | 10 из 15 типов; `PART_OF`, `HOSTED_ON` и служебные нет | `RelationType.java:13-27` 🟢 |
| Начало интервала | `validFrom` источника, иначе время события; повторное утверждение закрытой связи открывает её заново | `GraphProjector.java:567-575` 🟢 |
| Закрытие | версия записи замещает свои связи; tombstone и `CloseAssertion` закрывают явно; `validTo = max(validFrom, момент)` | `GraphProjector.java:519-545, 611-645, 668-690` 🟢 |
| Чтение «сейчас» | шаблоны `graph-query-core` берут связи с `validTo IS NULL` | ADR 0028; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java` 🟡 |
| Свежесть фактов | `stale-after = P7D`: `lastSeenAt` старше порога → `stale` в ответе MCP | `access/mcp-server/src/main/resources/application.yml:43-44`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:80` 🟢 |
| Миграция живого графа | `TemporalRelationMigration` при старте ingestion-service: `validFrom = coalesce(validFrom, firstSeenAt записи)`, иначе время миграции | ADR 0028; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/TemporalRelationMigration.java` 🟡 |

---

## 8. Бюджеты чтения (MCP и консоль)

- Предметные шаблоны с параметрами `$gid`, `$limit`; единственное допустимое место подстановки в Cypher — верхняя граница пути `{maxDepth}`, она проверяется регулярным выражением и числом из диапазона `1..ceiling` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:28, 60-137`) 🟢.
- Реестр отклоняет шаблон, чей литеральный `maxDepth` выше потолка (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:35-38`) 🟢.
- Исполнитель — `executeRead` в сессии `AccessMode.READ`; первый запуск шаблона сопровождается `EXPLAIN`, обязанным дать `READ_ONLY` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:27-36, 78-82, 115-118`) 🟢.
- Лимиты MCP: `max-depth 6`, `max-nodes 500`, `max-paths 50`, `timeout 5s`, `max-response-bytes 512KB` (`access/mcp-server/src/main/resources/application.yml:45-50`) 🟢. Консоль: окрестность ≤ 2, запрос поиска ≤ 200 символов, страница ≤ 50 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:41-44`) 🟢. `trace_dependencies` вне диапазона отклоняется ошибкой, без clamp (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:134-136`).

---

## 9. Что здесь НЕ реализовано (для следующих этапов)

- Автоматического или ручного разрешения конфликтов нет (§2.4 п. 7; [`state-machines.md`](state-machines.md), §4).
- Жизненного цикла кандидата, DLQ-replay-метки, `SUPERSEDED`, выхода из карантина, итогового `SyncRun.status` нет — [`detective-gaps.md`](detective-gaps.md).
- Retention и архивации нет (§5 п. 6).
