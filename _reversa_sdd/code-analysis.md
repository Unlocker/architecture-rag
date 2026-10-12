# Code Analysis → Технический анализ кода

> Archaeologist, этапы R.2a (UNLOCKER-263: `contracts/*`, разделы A, E, S) и R.2b (UNLOCKER-264: `adapters/*`, раздел R.2b / P в конце файла).
> Остальные модули (R.2c–R.2e) добавят свои разделы позже.
> Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Ссылки `файл:строка` даны относительно корня репозитория.
> Описания источников не дублируются: модель — `docs/02-data-model.md`, интерфейсы — `docs/03-interfaces.md`, решения — `docs/adr/`.

Сокращения путей: `CM` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel`, `ES` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas`, `SPI` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi`.

---

## Общие свойства трёх модулей

- Модули содержат только типы (records, sealed-интерфейсы, enums, интерфейсы-порты) и чистую логику без I/O; зависимостей на другие проектные модули нет (`contracts/*` — корень графа зависимостей, `_reversa_sdd/dependencies.md`) 🟢.
- Единый стиль валидации: проверки в компактных конструкторах records; `null` → `NullPointerException`, нарушение правила → `IllegalArgumentException` (`CM/Invariants.java:12-13`). В `event-schemas` и `source-spi` это соглашение выдержано не везде (см. §E, §S) 🟡.
- Каждый модуль несёт класс-маркер (`CanonicalModelModule`, `EventSchemasModule`) ради непустого JAR и smoke-теста (`CM/CanonicalModelModule.java:3`, `ES/EventSchemasModule.java:3`); у `source-spi` маркера нет 🟢.
- Время везде `Instant` (UTC) 🟢 — ловушка 10 из `CLAUDE.md` соблюдена: `LocalDateTime` в трёх модулях не используется.

---

## A. `contracts/canonical-model`

**Назначение.** Каноническая модель графа: типы узлов, типы связей, команды проекции, матрица авторитетности, provenance 🟢 (Javadoc пакетов; `docs/02-data-model.md` §2–4).
Состав: 38 файлов main — пакеты `node` (19), `relation` (2), `command` (6), `provenance` (7), `authority` (1), корень (`Invariants`, маркер, `package-info`). Тесты: 45 `@Test` (в т. ч. 4 `@ParameterizedTest` в `GitUrlsTest`) в 7 классах 🟢.

### A1. Поток управления и публичные операции

Модуль не содержит сервисов; «поток» — это конструкторные проверки и несколько чистых функций.

| Операция | Что делает | Где |
|---|---|---|
| `Invariants.requireText / optionalText` | обязательный и необязательный текст; пустая строка у необязательного **отвергается** (`null` — «не передано») | `CM/Invariants.java:21-35` 🟢 |
| `Invariants.copyProperties` | проверка и неизменяемая копия карты свойств связи | `CM/Invariants.java:49-58` 🟢 |
| `NodeLabel.isA` | транзитивная проверка подтипа по цепочке `supertype` | `CM/node/NodeLabel.java:54-61` 🟢 |
| `NodeLabel.canonical()` | все метки, кроме провенанс-меток | `CM/node/NodeLabel.java:64-71` 🟢 |
| `RelationType.allows(from, to)` | пара меток допустима с учётом подтипов | `CM/relation/RelationType.java:73-75` 🟢 |
| `AuthorityMatrix.isAuthoritative(...)` ×3 | авторитетность источника для узла / свойства / связи | `CM/authority/AuthorityMatrix.java:48-60` 🟢 |
| `GitUrls.normalize` | приведение URL репозитория к канону | `CM/node/GitUrls.java:30-74` 🟢 |
| `ComputeInstance.label()` | выбор метки по `kind` (`switch`) | `CM/node/ComputeInstance.java:35-41` 🟢 |
| `LookupKeys.repositoryUrl / itSystemCode` | фабрики ключей-псевдонимов для отложенных связей | `CM/provenance/LookupKeys.java:20-30` 🟢 |

Обработка ошибок: единственный механизм — исключения конструкторов; ни одного `catch` с проглатыванием. В `GitUrls.normalize` `URISyntaxException` перехватывается **без** `cause`, чтобы вход (с возможными учётными данными) не попал в сообщение (`CM/node/GitUrls.java:47-52`) 🟢 — выполнение ловушки 12.

### A2. Алгоритмы и встроенные правила

1. **Иерархия меток.** `VIRTUAL_MACHINE` и `PHYSICAL_SERVER` — подтипы `COMPUTE_INSTANCE` (`NodeLabel.java:23-24`); остальные метки плоские 🟢. Глубина > 1 поддержана циклом `isA`, но в данных не используется 🟡.
2. **Допустимость связи.** `sources.stream().anyMatch(from::isA) && targets.stream().anyMatch(to::isA)` (`RelationType.java:74`) 🟢. Пример: `RUNS_ON` объявлена на `COMPUTE_INSTANCE`, поэтому принимает и `VirtualMachine`, и `PhysicalServer`.
3. **Темпоральность и identity-свойства.** 11 из 15 типов связей `temporal=true` (`RelationType.java:13-24`); identity-свойства допустимы только у темпоральных, иначе `IllegalArgumentException` при инициализации enum (`RelationType.java:42-44`). Сейчас identity-свойство одно — `DEPLOYED_FROM{commit}` (ADR [0029](../docs/adr/0029-helm-chart-system-repo-commit.md)); смена такого свойства закрывает старую связь и открывает новую (`RelationType.java:48-52`) 🟢.
4. **Authority matrix.** Поиск точный и fail-closed: нет записи — пустое множество (`AuthorityMatrix.java:63-75`); свойство без собственного правила наследует правило метки, суперметка не опрашивается (`AuthorityMatrix.java:68-70`) 🟢. `MANUAL` не входит ни в одно правило (`AuthorityMatrix.java:79-113`) — «ручное разрешение» вынесено в отдельное утверждение (Javadoc `AuthorityMatrix.java:20-22`) 🟢.
5. **Нормализация Git URL** (`GitUrls.normalize`): scp-форма `git@host:org/repo` и схемы `ssh`/`git`/`https` → `https`; `http` остаётся `http`; порт сохраняется только для `http(s)`; схема и хост в нижнем регистре, путь — как есть; удаляются `.git` и хвостовые `/`; прочие схемы → `IllegalArgumentException("unsupported git url scheme")` (`GitUrls.java:40-73`, `76-91`) 🟢. Идемпотентность нормализации используется как инвариант `Deployment.repositoryUrl` (`Deployment.java:30-32`) 🟢.
6. **Валидация `Deployment`.** `sourceCommit` — ровно 40 hex в нижнем регистре; `eamSystemId > 0` (`Deployment.java:20, 27-35`) 🟢.
7. **Валидация `ComputeInstance`.** `hypervisorRef` только у VM, `serialNumber` только у `PHYSICAL_SERVER` (`ComputeInstance.java:26-31`) 🟢.
8. **Lifecycle.** `firstSeenAt ≤ lastSeenAt` и `isCurrent ⇔ deletedAt == null` (`Lifecycle.java:17-20`) 🟢.

### A3. Структуры данных

Полная таблица полей — в `data-dictionary.md` (раздел A). Кратко:

- `NodeData` — sealed-интерфейс с **11** вариантами (`NodeData.java:9-11`); `CanonicalNode<T extends NodeData>(gid, data, lifecycle)` — «хранимый» узел (`CanonicalNode.java:7`) 🟢.
- `GraphCommand` — sealed-интерфейс с **5** вариантами: `UpsertNode`, `UpsertRelation`, `CloseAssertion`, `TombstoneSourceRecord`, `DeferRelation` (`GraphCommand.java:7-8`) 🟢 — совпадает с `docs/02-data-model.md:193`.
- Ключи: `SourceKey(source, sourceType, sourceId)` (`SourceKey.java:8`), `SourceRecord(key, sourceVersion, contentHash, fetchedAt, active)` (`SourceRecord.java:9`) 🟢. Ключ `MERGE` для `SourceRecord` из `CLAUDE.md` (ловушка 4) совпадает с `SourceKey` 🟢.
- Provenance: `SourceSystem`, `SourceSystemCode {EAM, SCM, CMDB, DEPLOYMAP, MANUAL}`, `SyncRun`, `SyncRunStatus {RUNNING, SUCCEEDED, FAILED}` 🟢.

### A4. Константы, enums, конфигурация

| Enum | Значения | Файл |
|---|---|---|
| `NodeLabel` | 16 (13 канонических + 3 провенанс) | `node/NodeLabel.java:14-29` |
| `RelationType` | 15 (12 бизнес + 3 провенанс) | `relation/RelationType.java:13-27` |
| `Criticality` | LOW, MEDIUM, HIGH, CRITICAL | `node/Criticality.java:5` |
| `EnvironmentClass` | DEV, TEST, PREPROD, PROD | `node/EnvironmentClass.java:5` |
| `ComputeKind` | VIRTUAL_MACHINE, PHYSICAL_SERVER, UNSPECIFIED | `node/ComputeKind.java:5` |
| `SourceSystemCode` | EAM, SCM, CMDB, DEPLOYMAP, MANUAL | `provenance/SourceSystemCode.java:5` |
| `SyncRunStatus` | RUNNING, SUCCEEDED, FAILED | `provenance/SyncRunStatus.java:5` |

Feature flags и параметры окружения в модуле отсутствуют 🟢. Допустимые типы значений свойств связи: `String, Boolean, Long, Integer, Double (конечный), Instant` и список из них (`Invariants.java:60-77`) 🟢.

### A5. Расхождения и сомнения

- 🟡 **Две сущности `SourceSystem` и три представления кода источника.** `canonicalmodel.provenance.SourceSystem` — record (`SourceSystem.java:8`), `sourcespi.SourceSystem` — enum (`SPI/SourceSystem.java:4`), плюс `SourceSystemCode`. Имена различаются: `DEPLOYMAP` (`SourceSystemCode.java:5`) против `DEPLOY_MAP` (`SPI/SourceSystem.java:9`); коды lower-case (`"eam"`) против URN `urn:corp:eam` в событии. Место преобразования лежит за пределами `contracts/*` — уточнить в R.2b/R.2c.
- 🟡 **`ComputeInstance.label()` при `UNSPECIFIED` возвращает `COMPUTE_INSTANCE`**, но `HOSTED_ON` объявлена только для `VIRTUAL_MACHINE`→`PHYSICAL_SERVER` (`RelationType.java:22`); связь для нетипизированного узла невозможна. По `docs/02-data-model.md:77` нормализатор `HOSTED_ON` вообще не строит — расхождения нет, но инвариант «мёртвый» 🟡.
- 🟡 **Правило `platformId` в матрице не описано в `docs/02`.** Код фиксирует явные правила `ITSystem.platformId` и `Solution.platformId` (`AuthorityMatrix.java:95-96`), а таблица «Явные правила свойств» в `docs/02-data-model.md` (§3) называет только `criticality`, `eamCode`. Содержательного конфликта нет (правило повторяет узловое), но документ отстаёт от кода (ADR [0031](../docs/adr/0031-it-system-platform-invariant.md)).
- 🟡 **`CloseAssertion` не проверяет `validTo`** относительно `validFrom` связи: в конструкторе нет сравнения (`CloseAssertion.java:12-21`); `docs/02-data-model.md` (§4) возлагает это на проектор (`validTo = max(validFrom, validTo)`).
- 🟡 **`UpsertRelation` не проверяет `validity` на обязательность** для темпоральных типов: `validity` может быть `null` (`UpsertRelation.java:35-37`). Что проектор делает с `null`-validity у темпоральной связи, выясняется в R.2c.
- 🟡 **Имена ключей свойств `DEPENDS_ON`** (`kind`/`protocol`/`criticality`) не проверяются на этом уровне — явно оговорено (`UpsertRelation.java:18-19`).
- 🟡 **`Repository.archived`** — `Boolean`, `null` = неизвестно (`Repository.java:6`); без метаданных об авторитетности отдельного свойства `archived` и `defaultBranch` в матрице (`AuthorityMatrix.java:98`) правило наследуется от метки (SCM) 🟢.
- 🟡 **`SourceRecord` не содержит `deletedAt`**, которое есть в графе (`docs/02-data-model.md:58`): `deletedAt` в Java-типе отсутствует, активность выражает только `active` (`SourceRecord.java:9`).

---

## E. `contracts/event-schemas`

**Назначение.** Контракт события и журнала: `CanonicalEvent`, порты `EventJournal`, `JournalReader`, `CanonicalEventPublisher`, `RawPayloadStore`, а также состояния обработки и checkpoints 🟢 (`docs/02-data-model.md` §5; `docs/00-vision.md:376-387`).
Состав: 18 файлов main (16 типов + маркер + `package-info`), 12 `@Test` в 5 классах 🟢. Реализации (`PostgresEventJournal`, `S3RawPayloadStore`) лежат в `ingestion/event-journal` (`_reversa_sdd/soul.md` D3).

### E1. Поток управления (контракты портов)

Модуль не исполняет логики, кроме `ProcessingStatus.canTransitionTo`, `SourceVersion.compareTo`, конструкторных проверок. Контракты портов:

| Порт | Операции и гарантии | Где |
|---|---|---|
| `CanonicalEventPublisher` | `publish(event, payloadRef, syncRunId) → boolean` — at-least-once, `true` = принято впервые | `ES/CanonicalEventPublisher.java:7-14` 🟢 |
| `EventJournal` | `append` (атомарно, `RECEIVED`, повтор → `DUPLICATE` без изменения строки), `find`, `transition` (проверяет `canTransitionTo`), `toDlq` (идемпотентна на необработанную запись), `loadCheckpoint`/`saveCheckpoint`, `snapshotContents`, `objectsReceivedSince` | `ES/EventJournal.java:13-60` 🟢 |
| `JournalReader` | `read(JournalQuery)` — keyset-страница; `pending(after, retryNotAfter, limit)` — строки `RECEIVED` + `RETRYING`/промежуточные, не менявшиеся с `retryNotAfter` | `ES/JournalReader.java:12-28` 🟢 |
| `RawPayloadStore` | `put(source, bytes) → RawPayloadRef` под ключом `raw/<source>/<sha256>`; `get` проверяет hash | `ES/RawPayloadStore.java:3-10` 🟢 |

Ошибки: «ошибки хранилища не глушатся» — заявлено инвариантом в Javadoc `EventJournal` (`:10-11`) и `JournalReader` (`:9-10`) 🟢; исключения портов не объявлены (непроверяемые).

### E2. Алгоритмы и логика

1. **Автомат состояний** `ProcessingStatus.canTransitionTo` (`ES/ProcessingStatus.java:26-42`). Переход «в себя» разрешён всегда (`:27-29`). Ключевые правила: из `RECEIVED` — в `VALIDATED`, `RETRYING`, `QUARANTINED`, `DUPLICATE`, `IGNORED_OLD_VERSION`; `RESOLVED → PROJECTED`; `PROJECTED → SUPERSEDED` и больше никуда; `QUARANTINED → RETRYING` и больше никуда; `SUPERSEDED`/`DUPLICATE`/`IGNORED_OLD_VERSION` — терминальны. Диаграмма и таблица — в `flowcharts/event-schemas.md`; совпадает с `docs/02-data-model.md:239-270` 🟢.
2. **Сравнение версий** `SourceVersion` (`ES/SourceVersion.java:10-26`): значение — строка `[0-9]{1,40}`, сравнивается через `BigInteger`, т. е. `"99" < "184"`; до 40 цифр, ведущие нули допустимы (`"007"` и `"7"` равны по `compareTo`, но не по `equals`) 🟡. Нечисловая версия отвергается `IllegalArgumentException` («отклоняется на валидации»).
3. **Keyset-пагинация** `JournalKey` / `JournalQuery`: порядок `(receivedAt, source, eventId)`, `limit ∈ 1..1000`, окно `[receivedFrom, receivedTo)` (`ES/JournalQuery.java:25-33`) 🟢. `receivedFrom == receivedTo` отвергается (`:31`) — пустой интервал считается ошибкой 🟡.
4. **Префиксы eventId** в контракте: `replay:` (`ES/JournalQuery.java:22`) и `snap:<syncRunId>:...` (Javadoc `ES/EventJournal.java:53`); `reconcile:` и `snapshot-complete` в контракте не зафиксированы константами (`docs/02-data-model.md:288`) 🟡.
5. **`CanonicalEvent` как CloudEvent 1.0**: `specversion()` всегда `"1.0"` — метод, не поле record, поэтому в компонентах record его нет (`ES/CanonicalEvent.java:27-46`) 🟢. Ключ дедупликации — `(source, id)` (Javadoc `:8-9`).
6. **`AssetEventData.payload`**: `null`-значения сохраняются («поле не передано»), для этого `Map.copyOf` заменён обёрткой `unmodifiableMap(new LinkedHashMap)` (`ES/AssetEventData.java:22-23`) 🟢 — ловушка 5 из `CLAUDE.md` на уровне контракта.

### E3. Структуры данных

Поля — в `data-dictionary.md` (раздел E). Важное:

- `JournalEntry` — 16 полей, **нет** `type`/`subject`; они вынесены в `StoredEvent(entry, type, subject)` (`ES/JournalEntry.java:14-29`, `ES/StoredEvent.java:12`) 🟢.
- `SnapshotContents(objects, eventCount, firstReceivedAt)` — множество объектов копируется `Set.copyOf` (`ES/SnapshotContents.java:14-18`) 🟢.
- `Checkpoint(consumer, source, cursor, updatedAt)` — без проверок (`ES/Checkpoint.java:13`) 🟢.

### E4. Константы

`SPEC_VERSION = "1.0"`, `TYPE_ASSET_UPSERTED = "architecture.asset.upserted.v1"` (`ES/CanonicalEvent.java:28, 31`); `REPLAY_PREFIX = "replay:"`, `MAX_LIMIT = 1000` (`ES/JournalQuery.java:22, 25`) 🟢.

### E5. Расхождения и сомнения

- 🟡 **Типы `architecture.asset.deleted.v1` и `architecture.sync.snapshot-complete.v1` не объявлены в контракте.** В `CanonicalEvent` только константа upsert (`CanonicalEvent.java:31`), а `TYPE_ASSET_DELETED` живёт в `adapters/adapter-core/.../EventMapper.java:29`. Javadoc `CanonicalEvent` описывает его как событие `...upserted.v1` (`:6`), хотя тип — произвольная непустая строка (`:36`).
- 🟡 **`CanonicalEvent.subject` и `correlationid` не валидируются** (могут быть `null`) (`CanonicalEvent.java:34-40`) — совпадает с Javadoc («может быть `null`» для `correlationid`; для `subject` не оговорено).
- 🟡 **Соглашение об исключениях нарушено**: `CanonicalEvent`, `AssetEventData`, `RawPayloadRef` выбрасывают `IllegalArgumentException` и на `null` (`CanonicalEvent.java:49-51`, `AssetEventData.java:26-30`, `RawPayloadRef.java:10-11`), тогда как `JournalKey`/`StoredEvent` — `NullPointerException` (`JournalKey.java:14-17`). Внутри модуля единообразия нет; `Invariants` из `canonical-model` недоступен (модули независимы).
- 🟡 **`JournalEntry`, `ObjectRef`, `Checkpoint` без валидации** конструкторов — любые `null`/пустые значения проходят (`JournalEntry.java:14`, `ObjectRef.java:7`, `Checkpoint.java:13`).
- 🟡 **`EventJournal.saveCheckpoint` не проверяет монотонность** — явно переложено на вызывающего (`EventJournal.java:46-49`). Инвариант «checkpoint сдвигается только после коммита Neo4j» (ловушка 6) не выражен типами; опирается на соглашение. 🔴 Проверить в R.2c, что вызывающий код его соблюдает.
- 🟡 **Статусы `RETRYING`/промежуточные в `pending`** — одинаковая обработка «промежуточных» и «повторов» (`JournalReader.java:17-20`); это решение реализации, в `docs/00-vision.md:382-386` не показано.
- 🟡 **`SourceVersion` допускает ведущие нули** (`[0-9]{1,40}`), при этом `equals` двух эквивалентных по значению версий ложно (`SourceVersion.java:13`): сравнение на равенство версий обязано идти через `compareTo`. Использование проверяется в R.2c (`VersionDecision`).
- 🟡 `DUPLICATE` достижим только из `RECEIVED` (`ProcessingStatus.java:31-32`), что соответствует `docs/00-vision.md:386`. Дубль, обнаруженный уже после `VALIDATED` (например, равная версия на стадии проекции), в автомате выражается не через `DUPLICATE`; как это делает `EventProcessor`, проверяется в R.2c.

---

## S. `contracts/source-spi`

**Назначение.** SPI мастер-систем для адаптеров: чтение изменений, DTO, проверка HMAC-подписи webhook 🟢 (`SPI/package-info.java:1-4`; `docs/03-interfaces.md` §«Webhook»). Состав: 10 файлов, 4 `@Test` (`WebhookSignatureTest`) 🟢.

### S1. Поток управления

| Операция | Поведение | Где |
|---|---|---|
| `SourceConnector.fetchChanges(cursor, limit)` | страница изменений после курсора; `cursor == null` — полный snapshot с начала; 429/5xx/timeout → `SourceUnavailableException` | `SPI/SourceConnector.java:17-24` 🟢 |
| `SourceConnector.fetchById(type, id)` | актуальное состояние объекта; удалённый → `DELETE`; неизвестный → `Optional.empty()` | `SPI/SourceConnector.java:26-33` 🟢 |
| `WebhookSignature.sign` | `sha256=` + hex(HMAC-SHA256(secret, `ts + "." + body`)) | `SPI/WebhookSignature.java:47-49` 🟢 |
| `WebhookSignature.verify` | MALFORMED → BAD_SIGNATURE → OUT_OF_WINDOW → VALID | `SPI/WebhookSignature.java:58-86` 🟢 |

### S2. Алгоритмы и логика

1. **Порядок проверок `verify`** (`WebhookSignature.java:65-85`): 1) нет заголовка или нет префикса `sha256=` → `MALFORMED`; 2) timestamp не `long` → `MALFORMED`; 3) подпись не hex (`parseHex` падает) → `MALFORMED`; 4) сравнение `MessageDigest.isEqual` (постоянное время) → `BAD_SIGNATURE`; 5) окно `|now − ts| ≤ window` → иначе `OUT_OF_WINDOW`. Подпись проверяется **до** окна, поэтому `OUT_OF_WINDOW` означает «подпись верна, но старая/из будущего» 🟢. Совпадает с `docs/03-interfaces.md` (раздел «Подпись, timestamp, replay window»), где все три отказа дают одинаковый `401`.
2. **Секрет** используется только в `hmac`; в сообщениях исключений его нет (`WebhookSignature.java:88-95`, `93-94`) — ловушка 12 🟢. `SourceUnavailableException` включает только код источника и статус (`:19`) 🟢.
3. **Инвариант `ChangePage`**: `snapshotComplete` допустим только на странице с `hasMore == false` (`ChangePage.java:22-24`); список изменений копируется неизменяемо (`:21`) — `null` вместо списка даст `NullPointerException` 🟢. Смысл: удаление (missing set) только после полного snapshot (ловушка 9).
4. **`SourceChange.payload`** — неизменяемая обёртка с сохранением `null`-значений (`SourceChange.java:39-41`) 🟢 — ловушка 5.
5. **Кодировка подписи**: сравнение hex-значения регистронезависимо, так как `parseHex` принимает оба регистра, тогда как `docs/03-interfaces.md` требует «нижний регистр» от отправителя — проверяющая сторона мягче 🟡.

### S3. Структуры данных

Поля — в `data-dictionary.md` (раздел S). Важное: `SourceChange.sourceVersion` — `long` (`SourceChange.java:27`), тогда как в событии версия — `SourceVersion` (строка до 40 цифр) (`ES/SourceVersion.java:13`). `WebhookEvent` — запись без валидации (`WebhookEvent.java:18-25`) 🟢.

### S4. Константы

Заголовки `X-Archrag-Signature`, `X-Archrag-Timestamp`, `X-Archrag-Event-Id` (`WebhookSignature.java:25, 28, 31`); префикс `sha256=` (`:33`); коды источников `eam`, `scm`, `cmdb`, `deploymap` (`SourceSystem.java:5-9`) 🟢. Replay window по умолчанию (5 мин) в контракте **не** задан — это `AdapterConfig` (`docs/03-interfaces.md`) 🟡.

### S5. Расхождения и сомнения

- 🟡 **Заявленные инварианты `SourceChange` не проверяются конструктором.** Javadoc: «для `DELETE` payload пустой» и «`sourceVersion` монотонно растёт» (`SourceChange.java:12-14`); код проверяет только `null` (`:33-38`), а `sourceVersion` может быть отрицательным (`long`). `docs/03-interfaces.md` для `DELETE` требует «число ≥ 0» — это проверяется в `WebhookHandler`, а не в контракте.
- 🟡 **`long` против `SourceVersion` (≤ 40 цифр):** версия вида `10^30` не помещается в `SourceChange.sourceVersion`; конвертация `long → SourceVersion` лежит в `adapter-core` (R.2b).
- 🟡 **`WebhookEvent.source` — `String`**, а не `SourceSystem` (`WebhookEvent.java:21`); соответствие коду источника проверяет `WebhookHandler` (`docs/03-interfaces.md:358`).
- 🟡 **`WebhookSignature.verify` не отвергает пустой `secret`:** `SecretKeySpec` бросает `IllegalArgumentException` для пустого ключа (`WebhookSignature.java:91`) — исключение пробросится наружу, а не станет `MALFORMED`/`BAD_SIGNATURE`; требует решения вызывающей стороны. `now`/`window` на `null` — `NullPointerException`.
- 🟡 **`SourceSystem.DEPLOY_MAP`** (Javadoc ссылается на `stub.DeployMapFormat`, `SourceSystem.java:8`): класс существует в `platform/source-stubs/.../DeployMapFormat.java:24`, т. е. контракт ссылается на тестовые заглушки; формат «временный» 🟡.
- 🟡 **`SourceUnavailableException`** не передаёт `cause` и не хранит тело ответа — осознанно (секреты/недоверенный текст) 🟢; конструктор не проверяет `system == null` (NPE).
- 🟡 `Completeness.PARTIAL` означает «отсутствующие поля не считать удалением» (`Completeness.java:3-6`), но в самом SPI этот флаг ни на что не влияет — смысл реализует normalizer (R.2c).

---

## Итоги по модулям

| Модуль | Файлов main | Публичных типов | Узлов/команд/портов | Основные риски (см. §) |
|---|---|---|---|---|
| `canonical-model` | 38 | 36 | 11 `NodeData`, 5 `GraphCommand`, 15 `RelationType` | A5: `SourceSystem` ×3, `platformId` в `docs/02`, `CloseAssertion` |
| `event-schemas` | 18 | 16 | 4 порта, 10 статусов | E5: типы событий вне контракта, нет валидации `JournalEntry`, разнобой NPE/IAE |
| `source-spi` | 10 | 10 | 1 порт, 1 утилита подписи | S5: инварианты `SourceChange` только в Javadoc, `long` vs `SourceVersion` |

---

# R.2b. `adapters/*` (UNLOCKER-264)

> Archaeologist, этап R.2b. Охвачены `adapters/adapter-core`, `eam-adapter`, `scm-adapter`, `asset-adapter`, `deploymap-adapter`. Разделы contracts (A, E, S) выше не менялись.
> Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Ссылки `файл:строка` — от корня репозитория.
> Сокращения: `AC` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore`, `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `SC` = `adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter`, `AS` = `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter`, `DM` = `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter`.
> Контракты событий и интерфейсы не дублируются: `docs/03-interfaces.md` §3 (webhook), §5.4 (CloudEvent), `docs/06-eam-integration.md` (EAM), ADR 0009/0010.

## Общие свойства группы

- Группа делится на **ядро** (`adapter-core`: 13 файлов main, 50 тестов) и **четыре тонких привязки**. Привязки `scm`, `asset`, `deploymap` — копии одного шаблона по 39 строк, отличаются только константой `SYSTEM` (`SC/ScmAdapter.java:18`, `AS/AssetAdapter.java:18`, `DM/DeploymapAdapter.java:18`) 🟢. `eam` содержит собственный код: два коннектора и курсор (~590 строк, 48 тестов) 🟢.
- Зависимости: `adapter-core` → `source-spi`, `event-schemas`; `source-stubs` только в `test` (`adapters/adapter-core/pom.xml`). Neo4j-доступа и импорта `canonical-model`/`ingestion/*` в main нет 🟢 (`AC/package-info.java:3-5`, `AC/SourceAdapter.java:12-15`). `deploymap-adapter` тянет `canonical-model` и `source-stubs` только в `test` (`adapters/deploymap-adapter/pom.xml:33-40`) 🟢.
- Границы по `CLAUDE.md`: source DTO (`SourceChange`) не выходят из адаптера — наружу идёт только `CanonicalEvent` (`AC/EventMapper.java:14-16`); адаптер ничего не пишет в источник (только `GET`) 🟢.
- Единственная точка записи — `EventJournal.append` через `InboxWriter` (`AC/InboxWriter.java:34-48`); checkpoint-ы пишутся тем же `EventJournal` (`AC/Poller.java:136, 170-171`) 🟢.
- Время везде `Instant`/`Clock` (`Poller`, `WebhookHandler`) 🟢; секреты не попадают в `toString` (`AC/AdapterConfig.java:71-74`, `EA/EamAdapter.java:49-52`) и сообщения исключений (`EA/EamApiConnector.java:243-245`) 🟢.

---

## P1. `adapters/adapter-core`

**Назначение.** Общий код всех адаптеров: REST-коннектор по умолчанию, приём подписанного webhook, polling, полный snapshot с маркером, backoff, запись в inbox 🟢 (`AC/package-info.java:2-5`).

### P1.1 Поток управления

| Компонент | Что делает | Где |
|---|---|---|
| `SourceAdapter` | сборка коннектор + `WebhookHandler` + `Poller`; `startWebhook`, `startPolling`, `startReconciliation`, `close` | `AC/SourceAdapter.java:29-136` 🟢 |
| `Poller.pollOnce` | нет checkpoint → `snapshot`, иначе `incremental(cursor)` | `AC/Poller.java:89-96` 🟢 |
| `Poller.snapshotOnce` | полный snapshot при существующем курсоре (reconciliation) | `AC/Poller.java:103-105` 🟢 |
| `Poller.exclusive` | `ReentrantLock.tryLock`; занят → `ALREADY_RUNNING`; `GaveUp` → `GAVE_UP` | `AC/Poller.java:107-118` 🟢 |
| `Poller.fetch` | цикл повторов по `RetryPolicy` при `SourceUnavailableException` | `AC/Poller.java:191-204` 🟢 |
| `InboxWriter.record` | `rawStore.put` → `journal.append` | `AC/InboxWriter.java:34-48` 🟢 |
| `EventMapper.toEvent` / `snapshotComplete` | `SourceChange` → `CanonicalEvent`; маркер полного snapshot | `AC/EventMapper.java:52-95` 🟢 |
| `WebhookHandler.handle` | подпись → JSON → поля → `fetchById` → `record` → HTTP-статус | `AC/WebhookHandler.java:56-107` 🟢 |
| `WebhookServer` | JDK `HttpServer`: `POST /webhook`, `POST /control/snapshot`, лимит тела | `AC/WebhookServer.java:45-123` 🟢 |
| `HttpSourceConnector` | `GET /changes?limit&cursor`, `GET /objects/{type}/{id}` | `AC/HttpSourceConnector.java:51-78` 🟢 |
| `Json` | собственный парсер/писатель JSON (глубина ≤ 64) | `AC/Json.java:16-18, 27-40, 215` 🟢 |

Обработка ошибок (ловушка 14): ошибки источника → повтор/`GAVE_UP`; ошибки журнала и S3 **пробрасываются** (`AC/InboxWriter.java:19`, `AC/Poller.java:29`); в `SourceAdapter` и `WebhookServer` исключение логируется **только типом класса** и расписание продолжается (`AC/SourceAdapter.java:82-85, 107-110`, `AC/WebhookServer.java:119-122`) 🟢. Ни одного `catch (Exception) {}` без реакции: пустых обработчиков нет, `InterruptedException` восстанавливает флаг (`AC/Poller.java:209-211`, `AC/HttpSourceConnector.java:93-95`) 🟢. Исключение с проглатыванием «по умолчанию» — формат Retry-After не число → `null` (`AC/HttpSourceConnector.java:114-117`) 🟢 осознанно, комментарий в коде.

### P1.2 Алгоритмы и встроенные правила

1. **Инкрементальный polling** (`Poller.incremental`, `Poller.java:120-145`): читает страницы подряд; события страницы пишутся в inbox (`:126-129`), и только затем `saveCheckpoint(nextCursor)` (`:134-139`) — ловушка 6 в части «курсор после записи» 🟢. Страница с `hasMore` без нового курсора → `IllegalStateException` (защита от зацикливания недоверенным источником, `:130-133`) 🟢.
2. **Полный snapshot** (`Poller.snapshot`, `:147-174`): `syncRunId` берётся из checkpoint `<code>-snapshot` (`run:<id>`) или создаётся и сразу сохраняется (`:180-188`) — прерванный snapshot продолжается под тем же `syncRunId` 🟢. Все события snapshot пишутся с `correlationId = syncRunId` и `sync_run_id = syncRunId` (`:154`). Порядок завершения: **маркер → `done:<runId>` → переход на инкрементальный курсор** (`:168-172`) 🟢. Страница без `snapshotComplete` или без `nextCursor` на последней странице → `IllegalStateException`, маркер не пишется (`:164-167`) — ловушка 9 🟢.
3. **Идентификаторы событий** детерминированы: `poll:<type>/<id>/v<ver>`, `snap:<runId>:<type>/<id>/v<ver>`, `snapshot-complete:<runId>` (`EventMapper.java:98-105, 83`) 🟢. Перечитывание после сбоя даёт `DUPLICATE` (дедупликация `source + eventId` — на стороне журнала, R.2c) 🟡.
4. **`RetryPolicy.delay`** (`RetryPolicy.java:40-50`): `ceiling = min(maxDelay, base << min(attempt-1, 30))`, «equal jitter» `half + rnd[0..ceiling-half]`; `Retry-After` источника обрезается до `maxDelay` (`:46-48`) и используется, если больше backoff (`:49`). По умолчанию 5 попыток, 500 мс…30 с (`:31-33`) 🟢. Сдвиг ограничен 30, чтобы `long` не переполнился 🟢.
5. **Null-политика** (ловушка 5): `EventMapper.toEvent` отбрасывает `null`-значения **верхнего уровня** payload (`:57-61`); `DELETE` даёт пустой payload (`:56`); полнота — ключ `_completeness` (`:62`, константа `:35`) 🟢. Вложенные `null` (например внутри `attrs` EAM) **не** отбрасываются 🟡 (§P2.5).
6. **Webhook** (`WebhookHandler.handle`): порядок и коды ответов — `401` подпись (`:65-67`), `400` не JSON / нет полей / `source` ≠ код адаптера / `eventId` ≠ заголовку `X-Archrag-Event-Id` (`:68-83`), `503` источник недоступен (`:87-89`), `404` источник не знает объект (`:102`), `202` после записи (`:105-106`) 🟢. Для `DELETE`, когда источник объект забыл, tombstone строится по подписанному телу; `sourceVersion` из уведомления обязана быть числом ≥ 0, иначе `400` (`:93-100`) 🟢. Дедупликация идёт по `eventId` **из подписанного тела**, заголовок только сверяется (Javadoc `:27-28`) 🟢. Повторов `RetryPolicy` в webhook-пути нет (`docs/03-interfaces.md:351`) 🟢.
7. **`WebhookServer`**: тело ≤ `MAX_BODY_BYTES = 64 КБ`, чтение `readNBytes(MAX+1)` и `413` (`:30, 111-115`); не-POST → `405` (`:99-100`); необработанное исключение → `500` без деталей (`:119-122`); ответы без тела (`:104`); обработчики — виртуальные потоки (`:53`) 🟢. `POST /control/snapshot` синхронный, коды `202/409/503/500` (`:73-88`) и **без аутентификации** (Javadoc `:21-22`, `docs/03-interfaces.md:33, 368`) 🟢 — осознанный риск, граница — сеть compose.
8. **`HttpSourceConnector`**: `429` и `5xx` → `SourceUnavailableException(+Retry-After)` (`:98-100`); таймаут/обрыв → статус `0` (`:90-92`); любой другой не-200 → `IllegalStateException` без тела (`:104-108`); `404` на объект → `Optional.empty()` (`:73-75`); malformed JSON/изменение → `IllegalStateException` с причиной (`:121-127, 144-146`) 🟢. Тексты ответов источника в исключения и логи не попадают (ловушка 11) 🟢.
9. **Reconciliation-расписание** (`SourceAdapter.startReconciliation`, `:97-115`): первый запуск через `reconcileInterval` (по умолчанию 6 ч, `AdapterConfig.java:57`), общий `ReentrantLock` с polling (`:93-95`) 🟢. Замок внутрипроцессный — рассчитан на **один экземпляр** адаптера (Javadoc `Poller.java:34-35`) 🟢.

### P1.3 Структуры данных

Таблицы полей — `data-dictionary.md`, раздел P. Кратко: `AdapterConfig` (8 полей), `RetryPolicy` (3), `PollResult` (3 + `Outcome` из 5 значений), события `architecture.asset.upserted.v1`, `architecture.asset.deleted.v1`, `architecture.sync.snapshot-complete.v1`, raw-JSON в S3 для изменения и маркера 🟢.

### P1.4 Константы и конфигурация

`DEFAULT_REPLAY_WINDOW = 5 мин` (`AdapterConfig.java:32`), `pageSize = 100`, `pollInterval = 30 с` (`:61-62`), `DEFAULT_RECONCILE_INTERVAL = 6 ч` (`:57`), `MAX_BODY_BYTES = 64 КБ` (`WebhookServer.java:30`), connect-таймаут 5 с, запрос 10 с (`HttpSourceConnector.java:38, 83`), `Json.MAX_DEPTH = 64` (`Json.java:18`), схемы `urn:corp:schema:{asset-upserted,asset-deleted,snapshot-complete}:1` (`EventMapper.java:40-42`) 🟢. Конфигурация — через record `AdapterConfig`; env-переменных в модуле нет — их читает `platform/demo-launcher` (`docs/03-interfaces.md:316`) 🟢.

### P1.5 Сомнения и расхождения

- 🟡 **`AdapterConfig` проверяет не всё.** `replayWindow` и `pollInterval` на `null` дают `NullPointerException` на `.isNegative()` (`AdapterConfig.java:42, 48`), а не `requireNonNull` с именем поля, как у `system`/`retry` (`:35-38`). Пустой/пробельный `webhookSecret` отвергается (`:39-41`), но теста на это в `adapters/*/src/test` не нашлось (grep `blank`/`is required` находит только `blankTokenIsRefused` у EAM-токена) 🟡.
- 🟡 **`toString` `AdapterConfig` не показывает `pollInterval`, `reconcileInterval`, `retry`** (`:72-74`) — безопасно для секрета, но неполно для диагностики.
- 🟡 **Один и тот же `sourceVersion` у двух событий**: `poll:T/1/v3` и `snap:run:T/1/v3` — разные `eventId` с одинаковой версией объекта; корректность зависит от того, что projector не считает равную версию «старой» (R.2c).
- 🟡 **Reconciliation переписывает курсор.** `snapshot` по завершении безусловно сохраняет `page.nextCursor()` как курсор polling (`Poller.java:171`), даже если checkpoint уже был дальше; изменения между началом snapshot и его концом полагаются на перекрытие страниц и идемпотентность событий (для EAM это прямо оговорено, `EA/EamApiConnector.java:34-35`).
- 🟡 **`Json` — самописный парсер** (277 строк) вместо библиотеки; новая зависимость запрещена `CLAUDE.md`, поэтому это осознанно; покрыт 5 тестами (`JsonTest`). Числа парсятся в `Long`, иначе `Double` (`Json.java:169-171`) — большие целые > 2^63 станут `Double` 🟡.
- 🟡 **`WebhookHandler` не ограничивает длину `eventId`/`sourceType`/`sourceId`** (только `isBlank`, `:109-111`), кроме 64 КБ тела; значения идут в `eventId` и `subject`. Предел накладывает журнал — не проверялось (R.2c) 🟡.
- 🔴 **Тип `architecture.asset.deleted.v1` и `snapshot-complete.v1` объявлены в адаптере**, а не в контракте (§P1.6, сомнение R.2a п.3).

### P1.6 Сомнение R.2a п.3: `TYPE_ASSET_DELETED` в `EventMapper` — **подтверждено, 🟡/🔴**

- Определение: `AC/EventMapper.java:29` (`TYPE_ASSET_DELETED`), `:32` (`TYPE_SNAPSHOT_COMPLETE`), схемы `:40-42`. В контракте `CanonicalEvent` есть только `TYPE_ASSET_UPSERTED` (`contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEvent.java:31`) 🟢.
- Потребители не могут импортировать константу (по схеме зависимостей `ingestion/*` не зависит от `adapters/*`), поэтому **литерал продублирован**: `ingestion/graph-projector/.../EventProcessor.java:92-94` (`TYPE_ASSET_DELETED`, `TYPE_SNAPSHOT_COMPLETE`, `SCHEMA_ASSET_DELETED`), `ingestion/ingestion-service/.../StoredEventReader.java:34` (`TYPE_SNAPSHOT_COMPLETE`); интеграционные тесты используют голые строки (`platform/integration-tests/.../PlatformInvariantIT.java:136-137`, `GraphProjectorIT.java:140-141`) 🟢.
- Риск: переименование типа в одном месте не ломает компиляцию, тип событий разъедется молча. Тест на равенство литералов между `EventMapper` и `EventProcessor` не найден 🔴. Рекомендация для следующих этапов: вынести константы в `contracts/event-schemas` — правка кода вне границ R.2b.

---

## P2. `adapters/eam-adapter`

**Назначение.** Привязка к источнику `SourceSystem.EAM` плюс два коннектора для реального формата «EAM Tool API» (`docs/eam-api.yaml`) 🟢.

### P2.1 Поток управления

- `EamAdapter.create(config, journal, rawStore)` — стандартный `SourceAdapter` с `HttpSourceConnector` (`EA/EamAdapter.java:33-39`); `create(config, EamApi, journal, rawStore)` — **составной** коннектор: `EamApiConnector` (токен, формат eam-api) + `HttpSourceConnector` для «старой заглушки» (`:62-71`). Обе фабрики отвергают конфигурацию чужого источника `IllegalArgumentException` (`:35-37, 64-66`) 🟢.
- `CompositeEamConnector.fetchChanges` (`EA/CompositeEamConnector.java:50-70`): фаза A (eam-api) → фаза L (legacy); фаза переключается, когда `eam-api` вернул `hasMore=false` (`:57`); `snapshotComplete` ставится **только** на последней странице legacy-половины и только если legacy подтвердил полноту (`:65-69`) 🟢. Страницы eam-api всегда отдаются с `hasMore=true` и `snapshotComplete=false` (`:57-58`) — «последняя» страница определяется по legacy-фазе 🟢.
- `EamApiConnector.fetchChanges` (`EA/EamApiConnector.java:93-132`): типы читаются по очереди `PLATFORM → SOLUTION → IT_SYSTEM` (`:51-54`); snapshot — `sort=id`, инкремент — `sort=-mtime` до первого объекта с `datetime ≤ from` (`:107, 113-118`); конец типа — страница короче `page_size` или достигнут известный объект (`:124`); последняя страница последнего типа возвращает курсор-водяной знак `W,<watermark>` и `snapshotComplete = snapshot` (`:131`) 🟢.
- `EamApiConnector.fetchById` — `GET /api/<type>/{id}/`; нечисловой `sourceId` или неизвестный тип → `Optional.empty()` (`:135-153`); **`DELETE` никогда не порождается** (`change` всегда `UPSERT`, `:182-183`) — удаление обнаруживается только snapshot'ом (Reconciler) 🟢, `docs/06-eam-integration.md:60`.

### P2.2 Алгоритмы и правила

1. **Курсор `EamApiCursor`** (`EA/EamApiCursor.java:18-69`): три режима `S,<тип>,<стр>,<размер>,<wm>` / `I,<тип>,<стр>,<размер>,<от>,<wm>` / `W,<wm>`; разбор любой ошибки → `IllegalArgumentException("malformed eam-api cursor")` (`:59-61`), проверка числа частей и диапазонов (`:64-68`) 🟢. Курсор непрозрачен для `Poller` 🟢.
2. **Водяной знак** — максимум `datetime`, увиденный за проход (`EamApiConnector.java:120-122`); объект без `datetime` получает `Instant.EPOCH` (`:180-181`) 🟡 — такой объект в инкременте всегда «известен» и обрывает чтение типа.
3. **Лимиты (допущение 6, `docs/06-eam-integration.md:96`)**: `page_size ≤ 500` (`EamApiConnector.java:45, 82`), ≤ 10 запросов/с через `throttle` (`:249-260`, `MIN_INTERVAL = 100 мс`, `:56`), `429` с `Retry-After` — **одно** ожидание (≤ 30 с) и повтор, затем `SourceUnavailableException` (`:207-217`) 🟢.
4. **Аутентификация**: заголовок `Authorization: Token …` (`:227`), токен обязателен (`:75-77`), `401/403` → `IllegalStateException` без тела (`:241-245`) 🟢.
5. **Payload — весь `ArchObject` как есть** (`:182-183`: `payload = archObject` с `id`, `ver`, `datetime`, `attrs`). `EventMapper` отбрасывает `null` только на верхнем уровне, поэтому `attrs.<поле> = null` доходит до normalizer'а 🟡 — ловушка 5 переносится на `EamMapper` (R.2c).
6. **`sourceVersion = ver`**, `sourceId = id` без префикса (`:175-183`; `docs/06-eam-integration.md:61`); `ver`/`id` обязаны быть `Long`, иначе «malformed ArchObject» (`:190-195`) 🟢.
7. **Составной курсор** `S|I ; A|L ; eamapi:<base64url>|legacy:<base64url>` (`CompositeEamConnector.java:118-155`). Чужой формат (checkpoint старого адаптера) не падает навсегда: разбор → инкрементальное чтение с начала без маркера и без удалений, предупреждение в лог (`:78-85`) 🟢.
8. **Legacy-половина** отдаёт только `TEAM` и `SERVICE_DEPENDENCY`; `IT_SYSTEM` старой заглушки отфильтровывается (`:30, 61`) 🟢. Это долг PoC (Javadoc `:16-17`, `docs/06-eam-integration.md:127`).

### P2.3 Сомнения

- 🟡 **Пропуски при `sort=-mtime` во время записи** — компенсируются только периодическим snapshot'ом (`EamApiConnector.java:34-35`, допущение 6 `docs/06`).
- 🟡 **`datetime == null` → `EPOCH`** (`:180-181`): объект может не попасть в инкремент (см. §P2.2.2).
- 🟡 **`contentHash` считается от сырого JSON, не от канонизированного `attrs`**, идемпотентность держится на `ver` — известное расхождение, зафиксировано в `docs/06-eam-integration.md:61`; в коде адаптера `contentHash` не встречается (grep по `adapters/*/src/main`) 🟢.
- 🟢 **Лимит 10 запросов/с общий для polling и webhook-`fetchById`**: `throttle` синхронизирован (`EamApiConnector.java:249`), поля `lastRequestNanos`/`requested` читаются и пишутся только внутри него (`:65-66, 250-259`); при этом пауза выполняется под монитором, т. е. параллельные запросы выстраиваются в очередь 🟡 (на виртуальных потоках это допустимо).
- 🟡 **Webhook-путь EAM в реальности не используется** (в реальном EAM webhook'ов нет, `docs/06-eam-integration.md:64`), но `WebhookServer` стартует и для EAM — для заглушки.
- 🟡 **`EamAdapter.EamApi.token`**: токен хранится в record, в `toString` маскируется (`EamAdapter.java:49-52`); `equals`/`hashCode` record'а включают токен — не логируется, но попадёт в любое сравнение/ключ коллекции.

---

## P3. `adapters/scm-adapter`

Тонкая привязка к `SourceSystem.SCM` (`SC/ScmAdapter.java:15-39`): `config(baseUri, secret)` → `AdapterConfig.of` (`:23-25`), `create` → `SourceAdapter` со стандартным `HttpSourceConnector` (`:32-38`); отказ на чужой `config.system()` (`:34-36`) 🟢. Собственной логики нет. Тесты: `ScmAdapterContractTest` (6) и smoke (1) работают против `StubSources.scm` из `platform/source-stubs` 🟢.
Сомнение 🟡: класс-маркер `ScmAdapterModule` существует «ради непустого JAR» (`SC/ScmAdapterModule.java`, ср. `CM/CanonicalModelModule.java:3`) — тот же приём, что в contracts.

## P4. `adapters/asset-adapter`

Привязка к `SourceSystem.CMDB` (`AS/AssetAdapter.java:18`) — **имя модуля/класса `asset`/`Asset` не совпадает с кодом источника `cmdb`**; в документации связь описана (`docs/03-interfaces.md:322`: CMDB → `AssetAdapter` → `urn:corp:cmdb`) 🟢. Остальное — копия шаблона §P3. Тесты: 6 + 1 (`AssetAdapterContractTest`, `AssetAdapterSmokeTest`). В `modules.json` модуль описан под своим путём, источник — `CMDB`.

## P5. `adapters/deploymap-adapter`

Привязка к `SourceSystem.DEPLOY_MAP` (код `deploymap`) (`DM/DeploymapAdapter.java:18`). **Знания о формате deploy map/Helm в модуле нет**: main-код — тот же шаблон, а формат (`DeployMapFormat`, `deploymap-poc-0/1`) определяется в `platform/source-stubs` и разбирается в `ingestion/normalizer/.../DeployMapMapper.java` 🟢. Модуль содержит **контрактные тесты формата** (`DeployMapFixturesContractTest`, 9 тестов; `DeploymapAdapterContractTest`, 6; smoke, 1) и fixtures `src/test/resources/deploymap/*` (7 сценариев) 🟢 — это единственный «живой» документ временного формата на стороне адаптеров. См. раздел «Временные форматы» ниже.

---

## P6. Сомнения R.2a, отнесённые к `adapters/` — результат разбора

### П.1 `SourceSystem` / `SourceSystemCode`, `DEPLOYMAP` vs `DEPLOY_MAP`, `eam` → `urn:corp:eam` — **подтверждено как 🟡 (работает, но размазано и не защищено тестом)**

Цепочка преобразований (все звенья найдены):

| Звено | Значение | Где |
|---|---|---|
| enum адаптера | `EAM`, `SCM`, `CMDB`, `DEPLOY_MAP` | `contracts/source-spi/.../SourceSystem.java:5-9` |
| код источника | `eam`, `scm`, `cmdb`, `deploymap` | там же, `code()` `:18-20` |
| `source` события | `"urn:corp:" + code` → `urn:corp:eam` | `AC/EventMapper.java:47-49`, `AC/AdapterConfig.java:66-68` |
| имя consumer'а checkpoint | `<code>-poller`, `<code>-snapshot` | `AC/Poller.java:79-86` |
| enum модели | `EAM, SCM, CMDB, DEPLOYMAP, MANUAL` | `contracts/canonical-model/.../SourceSystemCode.java:5` |
| URN → `SourceSystemCode` (normalizer) | `switch` по `eam`/`scm`/`cmdb`/`deploymap`, иначе `null` | `ingestion/normalizer/.../Normalizer.java:93-101` |
| URN → `SourceSystemCode` (projector) | второй независимый `switch` | `ingestion/graph-projector/.../EventProcessor.java:633-646` |
| список кодов админки | `List.of("eam","scm","cmdb","deploymap")` | `access/admin-console/.../pg/SyncSources.java:19` |
| код из URN (ingestion) | `startsWith("urn:corp:")` → подстрока | `ingestion/ingestion-service/.../AdapterControlClient.java:33-35` |

Выводы:
1. Для четырёх источников преобразование сходится: `DEPLOY_MAP` → `"deploymap"` → `urn:corp:deploymap` → `DEPLOYMAP` 🟢. Расхождение имён `DEPLOY_MAP`/`DEPLOYMAP` не приводит к ошибке, пока соответствие идёт через строковый код, а не через `Enum.valueOf`/`name()`.
2. 🟡 Соответствие закодировано **минимум в пяти местах** строковыми литералами; общей функции нет. `valueOf(name())` в обход кода дал бы `DEPLOY_MAP` → исключение, потому что у `SourceSystemCode` нет такого имени. В `PostgresIdentity*` используется `SourceSystemCode.valueOf(rs.getString(..))` для значений, записанных как `name()` модели (`ingestion/identity-resolution/.../PostgresIdentityMapping.java:71`) — это другой enum и другой путь, но для `DEPLOYMAP` он совпадает по имени 🟢.
3. 🔴 Теста, сверяющего **все значения `SourceSystem` с `SourceSystemCode` (и `code()` с литералами `switch`)**, не найдено: `SourceSystem.values()` перебирают `LauncherConfig`, `IngestionMetrics` и интеграционные тесты, но не как проверку согласованности двух enum'ов. Добавление пятого источника потребует правки ≥ 5 мест без подсказки компилятора. Рекомендация вне R.2b: общий `SourceSystem.toCode()` или тест на соответствие.
4. 🟡 В Javadoc `SourceSystem.DEPLOY_MAP` (`SourceSystem.java:8`) указана ссылка `stub.DeployMapFormat`; фактический пакет — `io.github.unlocker.archrag.sourcestubs` (`platform/source-stubs/.../DeployMapFormat.java:24`) — ссылка устарела.

### П.3 `TYPE_ASSET_DELETED` в `EventMapper.java` — см. §P1.6 (**подтверждено, 🟡/🔴: константы продублированы без перекрёстной проверки**).

### П.8 `WebhookSignature` с пустым secret — **снято для адаптеров, остаётся 🟡 для прямых вызовов контракта**

- `WebhookSignature.hmac` создаёт `SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")` без проверки (`contracts/source-spi/.../WebhookSignature.java:88-95`); `SecretKeySpec` отвергает пустой массив ключа `IllegalArgumentException` (поведение JDK; здесь не запускалось) 🟡.
- В адаптерах этот путь **недостижим**: `AdapterConfig` отвергает `null`/пустой/пробельный `webhookSecret` ещё при создании (`AC/AdapterConfig.java:39-41`), а `WebhookHandler` берёт секрет только из `config` (`AC/WebhookHandler.java:59`) 🟢.
- Если бы исключение всё же возникло, оно попало бы в `catch (RuntimeException)` `WebhookServer.process` и стало `500` без деталей (`AC/WebhookServer.java:119-122`) — не `401` и не утечка секрета 🟢.
- Остаётся: (а) нет теста на отказ `AdapterConfig` при пустом секрете 🟡; (б) ложная защита контракта: `verify` для пустого секрета бросает, а не возвращает `BAD_SIGNATURE` — теоретически отличимо снаружи, но снаружи секрет не задаётся.

---

## P7. Временные форматы заглушек (deploy map, Helm chart, EAM API)

Формат каждой заглушки — временный, и PoC опирается на него в соответствии с ADR [0010](../docs/adr/0010-master-systems-are-stubs.md) («Адаптеры работают с заглушками, повторяющими зафиксированные контракты»). Ниже — что именно зафиксировано и где живёт; описания не дублируются.

### P7.1 Deploy map и Helm chart — `deploymap-poc-0` / `deploymap-poc-1`

- **Статус**: «ВРЕМЕННЫЙ собственный формат … после решения владельца формат и адаптер заменяются, а не расширяются» (`platform/source-stubs/.../DeployMapFormat.java:3-8`). Реального источника нет (`adapters/deploymap-adapter/src/test/resources/deploymap/README.md:3-5`). Основание — ADR [0009](../docs/adr/0009-deployment-from-deploymap.md): мастер `Deployment` — deploy map и Helm charts, читаются отдельным адаптером; «контракт deploy map нужно зафиксировать заглушкой» (раздел «Последствия» ADR 0009); дополняет ADR 0029 (`DEPLOYED_FROM{commit}`) 🟢.
- **Состав payload** (`DeployMapFormat.java:10-23`): `ENVIRONMENT {name}`; `DEPLOYMENT {service, environment, chart, chartVersion, hosts[]}`; в `poc-1` добавляется `chartMeta {name, version, appVersion, annotations{}}` — отражение `Chart.yaml`. Ключи аннотаций: `archrag.io/eam-system-id` (id объекта EAM, **не** `eam_id`), `archrag.io/git-repo-url` (нормализуется `GitUrls.normalize`), `archrag.io/git-commit` (40 hex, нижний регистр) (`DeployMapFormat.java:35-42`) 🟢. Ключи аннотаций — решение архитектора UNLOCKER-248 (README fixtures `:4-5`).
- **Совместимость**: `poc-0` остаётся валидным до перехода (`legacy-poc-0/`); строки `chart`/`chartVersion` дублируют `chartMeta` (`DeployMapFormat.java:19-22`) 🟢. Версия формата — это поле payload `format`, не `schemaVersion` события 🟢.
- **Где разбирается**: адаптер формат **не знает**; разбор — `ingestion/normalizer/.../DeployMapMapper.java` 🟢. Адаптер лишь переносит payload в `CanonicalEvent` (`EventMapper.java:57-62`).
- **Сценарии fixtures** (`deploymap/README.md`): `baseline`, `legacy-poc-0`, `unknown-repository`, `system-mismatch`, `invalid-commit`, `commit-change` (v1 → v2, `sourceVersion` 1 → 2) 🟢.
- **Риск H.2** (README `:24-27`): chart несёт `id` системы EAM (`5101`), а у сервиса SCM `systemCode` — `eam_id` (`EAM-1042`); сравнение для `Conflict` идёт через индекс `eam_id → id` (ADR [0024](../docs/adr/0024-open-source-id-format.md), ADR [0030](../docs/adr/0030-deployment-system-conflict.md)) 🟡.
- 🟡 **Дрейф**: Helm chart как артефакт не читается — «chart» представлен JSON-полями, а не `Chart.yaml`; выбор реального хранилища (Git, Helm-репозиторий, ConfigMap) отложен до решения владельца. Общий разрыв «контракты реальных EAM/SCM/CMDB/deploymap не проверены» — `docs/05-production-gap-list.md:43` (п. 3.5).

### P7.2 EAM API — `docs/eam-api.yaml`, заглушка `EamApiStub`

- **Статус**: PoC работает не с реальным EAM, а с `EamApiStub` в формате «EAM Tool API» v0.4 (`docs/06-eam-integration.md:3, 128`). Контракт и допущения — только в `docs/06-eam-integration.md` («Решения» 1–9) и `adapters/eam-adapter/src/test/resources/eam-api/README.md`; **всё, чего нет в спеке, — допущение**; подтверждено владельцем 2026-10-10/11, кроме `ownerTeam` (допущение 9) 🟢.
- **Что адаптер считает контрактом** (в коде): типы `platforms`, `itproducts` (как «ИТ-решение»; единственная константа `SOLUTION_TYPE`, `EA/EamApiConnector.java:48`), `itsystems` (`:51-54`); голый массив `ArchObject {id:int, ver:int, datetime, attrs}` (`:169-188`); пагинация `page`/`page_size`/`sort` (`:160`); конец выборки по неполной странице (`:124`); `page_size ≤ 500` и 10 rps (допущение 6); `Authorization: Token` (`:227`); архивный объект исчезает из списка и даёт 404 на `GET /{id}/` (допущение 4) 🟢.
- **Две половины одного источника**: `eam-api` для `PLATFORM/SOLUTION/IT_SYSTEM` и «старая заглушка» (`/changes`, `/objects/…`, id вида `EAM-1042`) для `TEAM` и `SERVICE_DEPENDENCY` (`CompositeEamConnector.java:13-26`, `docs/06-eam-integration.md:127`). Один коннектор вместо двух адаптеров, потому что `Reconciler` tombstone-ит все активные записи источника `EAM`, которых нет в прогоне (`CompositeEamConnector.java:19-22`). Долг PoC снимается задачей про реальный EAM.
- **Проверка контракта**: `EamApiFixturesContractTest` (14 тестов) сверяет fixtures с `EamApiFormat` из `source-stubs`; `EamApiConnectorTest` (18) и `CompositeEamConnectorTest` (9) проверяют поведение коннекторов 🟢. Fixtures не записаны с реального EAM, а собраны по спеке (README `:2-3`).
- 🟡 **Заведомые расхождения с реальностью, зафиксированные в коде/доках**: нет фильтра «изменено после T» (водяной знак по `-mtime`); нет webhook'ов; `contentHash` от сырого JSON; удаление определяется только snapshot'ом; `ownerTeam` — строковый код (допущение 9); при дубле `eamCode` связь SCM уходит на наименьший числовой `id` без логирования (`docs/06-eam-integration.md:121`).

### P7.3 Остальные заглушки (для полноты)

`StubSources.scm` и заглушка CMDB (старый формат `/changes`, `/objects/…`) обслуживают `scm-adapter` и `asset-adapter` через общий `HttpSourceConnector`; формат — `docs/03-interfaces.md` §5, раздел заглушек (`:536-538`). Реальные API SCM и CMDB не зафиксированы (ADR 0010).

---

## Итоги по модулям R.2b

| Модуль | Файлов main / тестов | Классов | Основные риски (см. §) |
|---|---|---|---|
| `adapter-core` | 13 / 50 `@Test` в 7 классах | 11 публичных типов, 1 пакетный (`InboxWriter`) | P1.5: проверки `AdapterConfig`; P1.6: дубль констант типов событий; `/control/snapshot` без аутентификации |
| `eam-adapter` | 6 / 48 в 5 классах | `EamAdapter`, `EamApiConnector`, `CompositeEamConnector`, `EamApiCursor` (пакетный) | P2.3: `datetime == null`, пропуски при `-mtime`; P7.2: допущения контракта |
| `scm-adapter` | 3 / 7 в 2 классах | `ScmAdapter` | P3: нет собственной логики |
| `asset-adapter` | 3 / 7 в 2 классах | `AssetAdapter` | P4: имя `asset` ≠ источник `cmdb` |
| `deploymap-adapter` | 3 / 16 в 3 классах | `DeploymapAdapter` | P5/P7.1: формат вне модуля, временный |

Новые сомнения R.2b (для комментария к задаче): 🟡 `AdapterConfig` (NPE вместо `requireNonNull`, нет теста на пустой секрет); 🟡 вложенные `null` в `attrs` EAM доходят до normalizer'а; 🟡 `datetime == null → EPOCH`; 🟡 одинаковая версия у `poll:` и `snap:` событий; 🔴 дубли литералов типов событий; 🔴 нет теста согласованности `SourceSystem` ↔ `SourceSystemCode`; 🟡 устаревшая ссылка в Javadoc `SourceSystem.DEPLOY_MAP`.

---

# R.2c — `ingestion/*` (UNLOCKER-265)

> Archaeologist, этап R.2c. Разделы Q1–Q5 — по одному на модуль ingestion; Q6 — разбор сомнений R.2a/R.2b; Q7 — итоги.
> Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Ссылки `файл:строка` — от корня репозитория.
> Сокращения: `EJ` = `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal`. Статусы и переходы `ProcessingStatus` описаны в `data-dictionary.md` §E.3, не дублируются.

## Q1. `ingestion/event-journal`

**Назначение.** Реализация портов `EventJournal`, `JournalReader`, `CanonicalEventPublisher` на PostgreSQL (чистый JDBC) и `RawPayloadStore` на S3; Flyway-миграция `V1__event_journal.sql` 🟢 (`EJ/PostgresEventJournal.java:31-38`, `EJ/S3RawPayloadStore.java:26-30`, `EJ/package-info.java:1-9`).
Состав: 5 классов main (`PostgresEventJournal`, `S3RawPayloadStore`, `JournalMigrations`, `JournalException`, `package-info`) + 1 SQL. Тесты модуля: только `S3RawPayloadStoreTest` (2 `@Test`) 🟢; поведение журнала на реальном PostgreSQL в модуле не тестируется — это делает `platform/integration-tests` (R.2e) 🟡.

### Q1.1 Поток управления

| Операция | Что делает | Где |
|---|---|---|
| `append(event, ref, syncRunId)` | `INSERT … ON CONFLICT (source,event_id) DO NOTHING RETURNING`; новая строка → `RECEIVED`, `attempts=0`; при конфликте читает существующую и **возвращает копию со статусом `DUPLICATE` только в памяти** (строка в БД не меняется) | `EJ/PostgresEventJournal.java:53-97` 🟢 |
| `publish` | `append(...).status() != DUPLICATE` | `:99-102` 🟢 |
| `find` | `SELECT … WHERE source=? AND event_id=?` | `:104-111` 🟢 |
| `transition` | `SELECT … FOR UPDATE` → `canTransitionTo` → `UPDATE … RETURNING`; одна транзакция | `:113-129`, `:320-354` 🟢 |
| `toDlq` | `transition(→QUARANTINED)` + вставка в `dlq_entry` в одной транзакции; `errorCode` обязателен | `:131-160` 🟢 |
| `read(JournalQuery)` | страница по keyset `(received_at, source, event_id)`, фильтры источник/интервал/`excludeReplays` | `:162-207` 🟢 |
| `pending(after, retryNotAfter, limit)` | выборка для диспетчера (см. Q1.2) | `:209-237` 🟢 |
| `loadCheckpoint` / `saveCheckpoint` | upsert в `consumer_checkpoint` по `(consumer, source)` | `:239-272` 🟢 |
| `snapshotContents(source, syncRunId)` | объекты и число событий snapshot-прогона | `:274-299` 🟢 |
| `objectsReceivedSince(source, since)` | `DISTINCT (source_type, source_id)` за период | `:301-318` 🟢 |
| `S3RawPayloadStore.put/get/ensureBucket/close` | запись по ключу `raw/<source>/<sha256>`, чтение с проверкой hash | `EJ/S3RawPayloadStore.java:59-113` 🟢 |

Обработка ошибок: `SQLException` → `JournalException` с cause (`PostgresEventJournal.java:94-96`, `EJ/JournalException.java:4-8`); при ошибке внутри транзакции — `rollback` и повторный `throw` (`:90-93`, `:122-125`, `:153-156`). Пустых `catch` нет 🟢. Исключение: `exists()` в S3 перехватывает `NoSuchKeyException` как штатный ответ «нет объекта» (`S3RawPayloadStore.java:92-99`) 🟢.

### Q1.2 Алгоритмы и встроенные правила

1. **Идемпотентность по `source + eventId`.** Обеспечена ограничением `uq_inbox_event UNIQUE (source,event_id)` и `ON CONFLICT DO NOTHING` (`V1__event_journal.sql:19`, `PostgresEventJournal.java:59`); тело дубля **не сравнивается** с первой записью — повтор с тем же `eventId` и другой версией/полезной нагрузкой молча отбрасывается 🟡.
2. **`DUPLICATE` не персистируется.** Статус `DUPLICATE` существует только в возвращаемом `JournalEntry` (`:89`, `:393-397`); в таблице строка остаётся в исходном статусе. Следовательно, `inbox_event.status = 'DUPLICATE'` в БД не появляется через `append`, хотя `ProcessingStatus` допускает `RECEIVED → DUPLICATE` 🟢/🟡 (используется ли переход `transition(...,DUPLICATE)` — см. Q5).
3. **Переходы под блокировкой.** `FOR UPDATE` берётся до проверки `canTransitionTo`, поэтому гонка двух воркеров исключена; запрещённый переход → `IllegalStateException` (не `JournalException`), неизвестное событие → `IllegalArgumentException` (`:322-338`) 🟢. Ошибка содержит только 12-символьный SHA-256-отпечаток `source+eventId`, не сами значения (`:356-359`) 🟢 (ловушка 11/12).
4. **`attempts`.** Растёт только при `status == RETRYING` (`+1` на каждый вызов, включая переход RETRYING→RETRYING) (`:345`) 🟢. `error_code`/`error_reason` **перезаписываются** при каждом переходе: переход без ошибки (например, → `VALIDATED`) обнуляет их (`:340-345`) 🟢 — история причин в строке не хранится.
5. **Выборка `pending`.** Условие: `status='RECEIVED'` (без задержки) **или** `status IN ('RETRYING','VALIDATED','NORMALIZED','RESOLVED') AND updated_at <= retryNotAfter` (`:214-215`) 🟢. `QUARANTINED`, `PROJECTED`, `DUPLICATE`, `SUPERSEDED`, `IGNORED_OLD_VERSION` не выбираются. Промежуточные статусы попадают в повтор по таймауту — защита от «зависшего» после падения процесса события 🟡 (намерение не задокументировано в коде). Порядок `received_at, source, event_id`, keyset-курсор `(received_at, source, event_id) > (?,?,?)` (`:216-217`) 🟢.
6. **`read`.** Динамический SQL собирается из фиксированных фрагментов, значения только параметрами (`:164`, `:194-196`) 🟢 (ловушка 1). `excludeReplays` — `event_id NOT LIKE 'replay:%'`, префикс — константа `JournalQuery.REPLAY_PREFIX` (`:179-182`) 🟢. `limit` берётся из `JournalQuery` (валидация в контракте, см. data-dictionary §E.2); в `pending` проверяется отдельно `1..MAX_LIMIT` (`:211-213`) 🟢.
7. **`snapshotContents`.** Отбор событий прогона через `starts_with(event_id, 'snap:'||syncRunId||':')`, а не `LIKE`, т.к. `syncRunId` приходит снаружи и может содержать `%`/`_` (`:276-284`) 🟢. Статус событий **не учитывается** (считаются и `QUARANTINED`); весь результат грузится в память (`HashSet<ObjectRef>`) 🟡 — для больших snapshot это линейная память.
8. **DLQ.** Вставка в `dlq_entry` идемпотентна: `ON CONFLICT (source,event_id) WHERE replayed_at IS NULL DO NOTHING` по частичному уникальному индексу `uq_dlq_entry_open` (`:140-142`, `V1__event_journal.sql:41`) 🟢. `reason` усекается до 500 символов (`MAX_REASON_LENGTH`, `:40`, `:399-402`); `error_reason` в `inbox_event` — тоже (`:344`) 🟢. `toDlq` из `PROJECTED`/конечных статусов невозможен (исключение перехода) 🟢.
9. **S3: ключ и целостность.** `put`: валидация `source` по `[A-Za-z0-9:._-]{1,200}` и запрет «только точки» (защита от `.`/`..` в ключе) → `key = raw/<source>/<sha256>`; `HEAD` и `PUT`, если нет (`:70-78`) 🟢. Гонка двух `put` одного содержимого безвредна (одинаковые байты) 🟡. `get`: пересчёт SHA-256 и `IllegalStateException` при расхождении (`:82-89`) 🟢; `NoSuchKeyException` из `get` не перехватывается и уходит вызывающему 🟢.
10. **Клиент S3.** path-style, регион-заглушка `us-east-1`, `WHEN_REQUIRED` для checksum-ов (комментарий: не все S3-совместимые хранилища понимают дефолтные доп. checksum SDK), HTTP-клиент `UrlConnectionHttpClient` (`:39-51`) 🟢. Ключи доступа приходят аргументами, в сообщения исключений не попадают 🟢.
11. **Миграции.** `JournalMigrations.apply` вызывает Flyway с `classpath:db/migration` (`EJ/JournalMigrations.java:11-13`). Каталог `db/migration` **разделён между модулями**: `V1` — здесь, `V2/V4/V5/V7` — `identity-resolution`, `V3/V6` — `ingestion-service` (см. Q3, Q5, раздел Q в `data-dictionary.md`). Итоговая схема получается только при запуске с classpath всех трёх модулей; в одиночку `event-journal` применяет лишь `V1` 🟢.

### Q1.3 Структуры данных

Таблицы `inbox_event`, `consumer_checkpoint`, `dlq_entry` и индексы — `data-dictionary.md` §Q.1. DTO (`JournalEntry`, `StoredEvent`, `Checkpoint`, `SnapshotContents`, `ObjectRef`) определены в `contracts/event-schemas` (R.2a, `data-dictionary.md` §E.2) и здесь только маппятся (`PostgresEventJournal.java:372-391`) 🟢.

### Q1.4 Константы и конфигурация

`MAX_REASON_LENGTH=500` (`:40`); `COLUMNS` — список 16 колонок для чтения (`:42-44`); `SOURCE_PATTERN` (`S3RawPayloadStore.java:33`); префиксы ключей `raw/`, `snap:` (`:74`, `PostgresEventJournal.java:284`). Конфигурация модуля — только аргументы конструкторов/`create`; свойства Spring лежат в `ingestion-service` (Q5).

### Q1.5 Сомнения и расхождения

- 🔴 **`dlq_entry.replayed_at` нигде не заполняется.** Колонка читается (`ingestion/ingestion-service/.../IngestionMetrics.java:71`, `access/admin-console/.../ConsoleReadRepository.java:141,158`), но в Java-коде нет ни одного `UPDATE dlq_entry`/`SET replayed_at` (`grep` по репозиторию, только main). Значит, запись остаётся «открытой» навсегда, метрика открытых DLQ-записей и фильтр консоли «replayed» не отражают реальность; повторный `toDlq` того же события после ручного replay не создаст новую запись из-за `uq_dlq_entry_open`. Нужно уточнить у архитектора, считается ли это намеренным (replay = создание нового события `replay:…`, а DLQ-запись остаётся историей) — см. Q5.
- 🟡 Тело дубля не сравнивается с оригиналом (п.1) — подмена содержимого при повторном `eventId` невидима.
- 🟡 `DUPLICATE` не сохраняется в БД (п.2); подсчёт дублей возможен только по логам/метрикам вызывающей стороны.
- 🟡 `error_code`/`error_reason` затираются при успешном переходе (п.4): после `RETRYING → PROJECTED` причина последней ошибки теряется (остаётся только `attempts`).
- 🟡 `snapshotContents` не фильтрует статус и держит всё в памяти (п.7).
- 🟡 Покрытие: единственный unit-тест модуля — S3 (2 теста); для журнала нет ни одного теста внутри модуля.

---

## Q2. `ingestion/normalizer`

Сокращение: `NZ` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer`.

**Назначение.** Чистая функция `CanonicalEvent + RawPayloadRef → NormalizationResult` (`Normalized(commands, unresolved, warnings)` либо `Quarantined(code, reason)`); единственное место, где существует source DTO (payload `Map<String,Object>`) (`NZ/Normalizer.java:14-24`, `NZ/CanonicalMapper.java:10-13`, `NZ/NormalizationResult.java:6-34`) 🟢.
Состав: 14 файлов main (`Normalizer`, 4 маппера, `CanonicalMapper`, `CommandSink`, `Payload`, `NormalizationResult`, `NormalizationException`, `UnresolvedReference`, `ReferenceResolver`, `DeploymentLookup`, маркер, `package-info`). Тесты: 71 `@Test` в 5 классах (`NormalizerTest` 35, `EamArchObjectMapperTest` 16, `DeployMapMapperTest` 16, `ScmMapperTest` 3, smoke 1) + `Fixtures`; `platform/source-stubs` — тестовая зависимость для фикстур (`ingestion/normalizer/pom.xml:30-34`) 🟢.

### Q2.1 Поток управления

`Normalizer.normalize` (`NZ/Normalizer.java:54-86`):

1. тип события ≠ `architecture.asset.upserted.v1` → `Quarantined(UNSUPPORTED_EVENT_TYPE)` (`:55-57`);
2. `sourceCode(urn)` → `SourceSystemCode`; нет маппера → `UNKNOWN_SOURCE` (`:58-62`);
3. версия схемы — суффикс `dataschema` после `urn:corp:schema:asset-upserted:`; нет в `supportedSchemaVersions()` → `UNKNOWN_SCHEMA_VERSION` (`:63-66`; все 4 маппера поддерживают только `"1"`);
4. собирается `SourceRecord(SourceKey, sourceVersion, raw.contentHash(), event.time(), active=true)` (`:68-75`);
5. маппер пишет в `CommandSink`; `NormalizationException` → `Quarantined(e.code(), e.getMessage())`, `IllegalArgumentException` (нарушение инварианта record'а модели) → `INVALID_PAYLOAD` с **фиксированным** текстом без значений (`:76-84`);
6. `sink.result()`.

Маппер по `sourceType` ключа выбирает ветку; неизвестный `sourceType` → `UNKNOWN_SOURCE_TYPE` (`EamMapper.java:116`, `ScmMapper.java:95`, `CmdbMapper.java:32-34`, `DeployMapMapper.java:134`) 🟢.

### Q2.2 Алгоритмы и встроенные правила

1. **`Payload` — единая семантика «поле не передано».** `null`, отсутствие и пустая/пробельная строка → `Optional.empty()` (`NZ/Payload.java:30-41`); значение неверного типа → `INVALID_PAYLOAD` с именем поля (`:124-126`); `optionalList` отбрасывает пустые элементы, но не-строка → ошибка (`:89-107`); `optionalIdRef` принимает только `Long`/`Integer` (`:113-122`) 🟢. Строки «ужимаются» `strip()` — значения из источника **нормализуются по пробелам** до записи 🟢.
2. **Неполная запись не затирает известное (ловушка 5).** Необязательные поля узла передаются как `null`, а не как значение по умолчанию (`CmdbMapper.java:36-48`, `ScmMapper.java:57-58`, `EamMapper.java:145-148`); запись `null` в граф должна блокироваться проектором (`NZ/NormalizationResult.java:12-17`, проверка — Q4) 🟢. Для CMDB `kind` обязателен именно потому, что подстановка `UNSPECIFIED` затёрла бы известный тип (`CmdbMapper.java:36-37`).
3. **Вложенные `null` в `attrs` EAM (сомнение R.2b).** `Payload.optionalObject` копирует вложенную карту в `HashMap` с `null`-значениями (`:52-59`), поэтому `{"attrs":{"description":null,"platform_id":null}}` не падает: `optional(...)` и `optionalIdRef(...)` читают `null` как «не передано» (`EamMapper.java:145-148, 157, 161, 177-179`). Итог: обязательные `name` → `MISSING_REQUIRED_FIELD`, необязательные → `null` → граф не затирается; связи `USES_*` из `null`-ссылок **не строятся и не закрываются** (`EamMapper.java:208-214`) 🟢. **Сомнение снято: падения нет, но и «явного удаления» через `null` нет.** Побочное: сам `attrs: null` у `IT_SYSTEM` неотличим от плоского формата и ведёт в плоскую ветку → `MISSING_REQUIRED_FIELD: name` (`:73-76, 81`) 🟡 — причина вводит в заблуждение.
4. **Критичность EAM: две таблицы.** Для ArchObject — `Map.of("HIGH","MEDIUM","LOW")`, остальное → `criticality=null`, сырое значение сохраняется в `criticalityRaw`, в warnings — `UNMAPPED_CRITICALITY` (`EamMapper.java:128-130, 161-168, 175-178`); для плоского формата — `Criticality.valueOf` (включая `CRITICAL`), неизвестное → `INVALID_PAYLOAD` (`:120-126`) 🟡 — значение `CRITICAL` в ArchObject уходит в `criticalityRaw`, в плоском — принимается; расхождение намеренное или нет, из кода не видно.
5. **Два формата EAM** выбираются по `sourceType` и наличию `attrs`: `PLATFORM`/`SOLUTION` — всегда ArchObject; `IT_SYSTEM` — ArchObject при `attrs`, иначе плоский (без `eamCode`, «только реплей старого журнала») (`EamMapper.java:69-84`, Javadoc `:30-34`); `TEAM`, `SERVICE_DEPENDENCY` — плоские 🟢. Поля ArchObject `id/ver/datetime` маппер **не читает**: ключ и версия приходят из `SourceKey`/`sourceVersion` события, время — из `event.time()` 🟢.
6. **Связи и отложенные ссылки.** `CommandSink.relation` определяет «другой конец» (`other = from.equals(self) ? to : from`), проверяет `resolver.exists(other)`; неизвестен — `UnresolvedReference` с полной `UpsertRelation`, фиктивный узел не создаётся (`NZ/CommandSink.java:53-69`). `link` (связь между двумя внешними сторонами, `DEPENDS_ON`) — отдельная `UnresolvedReference` на каждую неизвестную сторону; команда добавляется, только если известны обе (`:80-102`) 🟢. Допустимость пары меток и identity-свойств проверяет конструктор `UpsertRelation` → нарушение превращается в `INVALID_PAYLOAD` (`contracts/canonical-model/.../command/UpsertRelation.java:32-41`).
7. **Alias-ключи.** SCM `systemCode` → `lookup.itSystemByEamCode(code)` или `LookupKeys.itSystemCode(code)`; deploy map `git-repo-url` → `lookup.repositoryByUrl(url)` или `LookupKeys.repositoryUrl(url)` — неразрешённые ссылки уходят в отложенные и достраиваются проектором (`ScmMapper.java:68-82`, `DeployMapMapper.java:194-198`) 🟢.
8. **Выбор сервиса для `HAS_DEPLOYMENT`** (только `deploymap-poc-1`): (1) `service` из payload, если он среди `servicesImplementedIn(repo)`; (2) единственный сервис репозитория; (3) `service` из payload как есть (отложенная связь); (4) иначе предупреждение `DEPLOYMENT_SERVICE_UNRESOLVED` (`DeployMapMapper.java:209-224`). Снимок на момент нормализации, без переоценки (Javadoc `:38-42`); переоценку делает `EventProcessor` (Q4/Q5, `EventProcessor.java:443-470`) 🟢.
9. **`deploymap-poc-1`.** Нужны три аннотации `archrag.io/{eam-system-id,git-repo-url,git-commit}` (иначе `MISSING_REQUIRED_FIELD`); commit — `[0-9a-f]{40}`; `eam-system-id` — положительное `long` из цифр; URL нормализуется `GitUrls.normalize` (`DeployMapMapper.java:142-190`). Неизвестный `format` → `INVALID_PAYLOAD` (`:143-146`). Класс окружения — `class` или код окружения, затем `switch` по строке (`dev|development`, `test|qa`, `preprod|stage|staging`, `prod|production`), иначе `INVALID_PAYLOAD` (`:244-252`) 🟢.
10. **Время связей.** `validity` создаётся только как `Validity(validFrom, null)` при наличии `validFrom`/`ownerSince` в payload, иначе `null` — «выбор за проектором» (`EamMapper.java:94, 112, 192`, `ScmMapper.java:115-117`, `DeployMapMapper.java:240-242`) 🟢. `Deployment.observedAt = event.time()` (`DeployMapMapper.java:105`).
11. **Warnings** — только коды без значений: `DEPENDS_ON_NOT_SUPPORTED` (`EamMapper.java:96-98`), `UNMAPPED_CRITICALITY`, `DEPLOYMENT_SERVICE_UNRESOLVED`. Единственное логирование — `DEBUG` без значения (`EamMapper.java:167`) 🟢 (ловушка 11).

### Q2.3 Структуры данных

Выход — `NormalizationResult` (`Normalized`: три неизменяемых списка; `Quarantined`: код и причина). Вход маппера — `Input(record, payload, time)` (`NZ/CanonicalMapper.java:36-42`). Форматы payload по источникам — `data-dictionary.md` §Q.2.

### Q2.4 Константы

Префиксы `urn:corp:` и `urn:corp:schema:asset-upserted:` (`Normalizer.java:27-28`); коды ошибок: `UNSUPPORTED_EVENT_TYPE`, `UNKNOWN_SOURCE`, `UNKNOWN_SCHEMA_VERSION`, `UNKNOWN_SOURCE_TYPE`, `MISSING_REQUIRED_FIELD`, `INVALID_PAYLOAD`; `sourceType` по источникам: EAM `PLATFORM|SOLUTION|IT_SYSTEM|TEAM|SERVICE_DEPENDENCY`, SCM `REPOSITORY|SERVICE`, CMDB `COMPUTE_INSTANCE`, DEPLOYMAP `ENVIRONMENT|DEPLOYMENT` 🟢.

### Q2.5 Сомнения и расхождения

- 🟡 **Ручное сопоставление кодов источников (R.2a/R.2b п.1).** `Normalizer.sourceCode` (`Normalizer.java:92-103`) — `switch` по строке `eam|scm|cmdb|deploymap` → `SourceSystemCode`. Адаптерная сторона (`adapters/adapter-core/.../EventMapper.java:47-49`) формирует `urn:corp:<код>`, поэтому для `asset` источник — `cmdb`. Сопоставление **продублировано вручную** в трёх местах (адаптер, `Normalizer`, `EventProcessor.java:633-646` — см. Q4/Q6), общего источника правды и теста «все `SourceSystemCode` покрыты» нет. Подтверждено: работает, не защищено от рассинхрона.
- 🟡 Не-`IllegalArgumentException` (в частности `NullPointerException` из `Objects.requireNonNull` в record'ах или `raw == null`, `Normalizer.java:72`) из `normalize` **не перехватываются** и уходят вызывающему; как с ними поступает `EventProcessor` — Q4. `raw` не проверяется на `null`, хотя журнал допускает `payload_key IS NULL` (`PostgresEventJournal.java:75-76`).
- 🟡 Конструктор `Normalizer` молча перезаписывает маппер при двух мапперах одного источника (`Normalizer.java:35`).
- 🟡 Два разных словаря критичности (п.4).
- 🟡 `attrs: null` у IT_SYSTEM → вводящая в заблуждение причина (п.3).
- 🟡 TODO в коде: функции нормализации Git URL в `ScmMapper` (`GitUrls.normalize`) и `FeatureNormalizer.repositoryUrl` (identity-resolution) различаются (`ScmMapper.java:103-104`).
- 🟢 `platform/source-stubs` подключён со `scope=test` (`ingestion/normalizer/pom.xml:30-34`): зависимость только для фикстур, на схему модулей не влияет.
