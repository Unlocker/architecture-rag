# Code Analysis → Технический анализ кода

> Archaeologist, этапы R.2a (UNLOCKER-263: `contracts/*`, разделы A, E, S), R.2b (UNLOCKER-264: `adapters/*`, раздел R.2b / P) и R.2c (UNLOCKER-265: `ingestion/*`, раздел «R.2c — `ingestion/*`», Q1–Q7, в конце файла).
> Остальные модули (R.2d–R.2e) добавят свои разделы позже.
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
Состав: 5 Java-файлов main, 550 строк (`PostgresEventJournal`, `S3RawPayloadStore`, `JournalMigrations`, `JournalException`, `package-info`) + 1 SQL. Тесты модуля: только `S3RawPayloadStoreTest` (2 `@Test`) 🟢; поведение журнала на реальном PostgreSQL в модуле не тестируется — это делает `platform/integration-tests` (R.2e) 🟡.

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

1. **Идемпотентность по `source + eventId`.** Обеспечена ограничением `uq_inbox_event UNIQUE (source,event_id)` и `ON CONFLICT DO NOTHING` (`V1__event_journal.sql:21`, `PostgresEventJournal.java:59`); тело дубля **не сравнивается** с первой записью — повтор с тем же `eventId` и другой версией/полезной нагрузкой молча отбрасывается 🟡.
2. **`DUPLICATE` не персистируется.** Статус `DUPLICATE` существует только в возвращаемом `JournalEntry` (`:89`, `:393-397`); в таблице строка остаётся в исходном статусе. Следовательно, `inbox_event.status = 'DUPLICATE'` в БД не появляется через `append`, хотя `ProcessingStatus` допускает `RECEIVED → DUPLICATE` 🟢/🟡 (переход `RECEIVED → DUPLICATE` используется проекцией: `EventProcessor.decideBeforeWrite`, Q4.2 п.1 — там он персистируется).
3. **Переходы под блокировкой.** `FOR UPDATE` берётся до проверки `canTransitionTo`, поэтому гонка двух воркеров исключена; запрещённый переход → `IllegalStateException` (не `JournalException`), неизвестное событие → `IllegalArgumentException` (`:322-338`) 🟢. Ошибка содержит только 12-символьный SHA-256-отпечаток `source+eventId`, не сами значения (`:356-359`) 🟢 (ловушка 11/12).
4. **`attempts`.** Растёт только при `status == RETRYING` (`+1` на каждый вызов, включая переход RETRYING→RETRYING) (`:345`) 🟢. `error_code`/`error_reason` **перезаписываются** при каждом переходе: переход без ошибки (например, → `VALIDATED`) обнуляет их (`:340-345`) 🟢 — история причин в строке не хранится.
5. **Выборка `pending`.** Условие: `status='RECEIVED'` (без задержки) **или** `status IN ('RETRYING','VALIDATED','NORMALIZED','RESOLVED') AND updated_at <= retryNotAfter` (`:214-215`) 🟢. `QUARANTINED`, `PROJECTED`, `DUPLICATE`, `SUPERSEDED`, `IGNORED_OLD_VERSION` не выбираются. Промежуточные статусы попадают в повтор по таймауту — защита от «зависшего» после падения процесса события 🟡 (намерение не задокументировано в коде). Порядок `received_at, source, event_id`, keyset-курсор `(received_at, source, event_id) > (?,?,?)` (`:216-217`) 🟢.
6. **`read`.** Динамический SQL собирается из фиксированных фрагментов, значения только параметрами (`:164`, `:194-196`) 🟢 (ловушка 1). `excludeReplays` — `event_id NOT LIKE 'replay:%'`, префикс — константа `JournalQuery.REPLAY_PREFIX` (`:179-182`) 🟢. `limit` берётся из `JournalQuery` (валидация в контракте, см. data-dictionary §E.2); в `pending` проверяется отдельно `1..MAX_LIMIT` (`:211-213`) 🟢.
7. **`snapshotContents`.** Отбор событий прогона через `starts_with(event_id, 'snap:'||syncRunId||':')`, а не `LIKE`, т.к. `syncRunId` приходит снаружи и может содержать `%`/`_` (`:276-284`) 🟢. Статус событий **не учитывается** (считаются и `QUARANTINED`); весь результат грузится в память (`HashSet<ObjectRef>`) 🟡 — для больших snapshot это линейная память.
8. **DLQ.** Вставка в `dlq_entry` идемпотентна: `ON CONFLICT (source,event_id) WHERE replayed_at IS NULL DO NOTHING` по частичному уникальному индексу `uq_dlq_entry_open` (`:140-142`, `V1__event_journal.sql:47-48`) 🟢. `reason` усекается до 500 символов (`MAX_REASON_LENGTH`, `:40`, `:399-402`); `error_reason` в `inbox_event` — тоже (`:344`) 🟢. `toDlq` из `PROJECTED`/конечных статусов невозможен (исключение перехода) 🟢.
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
Состав: 15 Java-файлов main, ≈1200 строк (`Normalizer`, 4 маппера, `CanonicalMapper`, `CommandSink`, `Payload`, `NormalizationResult`, `NormalizationException`, `UnresolvedReference`, `ReferenceResolver`, `DeploymentLookup`, маркер, `package-info`). Тесты: 71 `@Test` в 5 классах (`NormalizerTest` 35, `EamArchObjectMapperTest` 16, `DeployMapMapperTest` 16, `ScmMapperTest` 3, smoke 1) + `Fixtures`; `platform/source-stubs` — тестовая зависимость для фикстур (`ingestion/normalizer/pom.xml:30-34`) 🟢.

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

---

## Q3. `ingestion/identity-resolution`

Сокращение: `IR` = `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution`.

**Назначение.** Три ступени идентичности, всё в PostgreSQL (JDBC): (1) детерминированный `(source, sourceType, sourceId) → gid` и approved crosswalk; (2) признаки и кандидаты на совпадение; (3) утверждения свойств и конфликты источников по `AuthorityMatrix` (`IR/package-info.java:1-18`) 🟢.
Состав: 19 Java-файлов main, ≈1300 строк — интерфейсы `IdentityMapping`, `IdentityCandidates`, `SourceConflicts`; реализации `PostgresIdentityMapping`, `PostgresIdentityCandidates`, `PostgresSourceConflicts`; чистые `FeatureExtractor`, `FeatureNormalizer`, `FeatureKind`, `LabelFamily`, `SourceKeys`; записи `Crosswalk`, `Feature`, `IdentityCandidate`, `SourceConflict`; исключения `CrosswalkConflictException`, `IdentityStoreException`; маркер и `package-info`; миграции `V2`, `V4`, `V5`, `V7`. Тесты: 20 `@Test` в 4 классах (`FeatureExtractorTest` 8, `FeatureNormalizerTest` 7, `CrosswalkTest` 4, smoke 1); **реализации Postgres в модуле не тестируются** (их покрывают IT в других модулях, R.2e) 🟡.

### Q3.1 Поток управления

Вызывающая сторона — `EventProcessor` (Q4): `IdentityMapping.resolve` для всех ключей события → `FeatureExtractor.extract` → `IdentityCandidates.record` → `SourceConflicts.assertProperties` / `assertRelation` / `retract`; `CrosswalkService` (Q5) вызывает `approve`.

| Операция | Что делает | Где |
|---|---|---|
| `IdentityMapping.find` / `resolve` | чтение / `INSERT … ON CONFLICT DO NOTHING` + чтение; параллельные вызовы сходятся к одному `gid` | `IR/PostgresIdentityMapping.java:36-60, 124-132` 🟢 |
| `keysOf(gid)` | все ключи `gid`, порядок по `source, source_type, source_id` | `:62-78` 🟢 |
| `approve(Crosswalk)` | нормализует порядок пары, даёт обоим ключам общий `gid`, пишет `approved_crosswalk` (идемпотентно) | `:80-101, 103-118, 134-145` 🟢 |
| `IdentityCandidates.record` | под advisory-lock'ами заменяет признаки ключа, ищет совпадения, upsert кандидатов | `IR/PostgresIdentityCandidates.java:78-102` 🟢 |
| `forget` / `candidatesOf` | удаляет признаки ключа (кандидаты остаются) / читает кандидатов пары | `:104-120, 122-152` 🟢 |
| `SourceConflicts.assertProperties` | заменяет утверждения записи, пересчитывает конфликты `gid` | `IR/PostgresSourceConflicts.java:81-103` 🟢 |
| `assertRelation` | конфликт вида `RELATION` (ссылка расходится с ключом из другого источника) | `:105-119` 🟢 |
| `retract` | tombstone: удаляет утверждения, закрывает `RELATION`-конфликты записи, пересчитывает | `:121-131` 🟢 |

Все операции — одна транзакция; `SQLException` → `IdentityStoreException` с cause; конфликт crosswalk → `CrosswalkConflictException` (не обёрнут) (`PostgresIdentityMapping.java:94-100`, `PostgresSourceConflicts.java:146-160`) 🟢. Пустых `catch` нет.

### Q3.2 Алгоритмы и встроенные правила

1. **`gid` назначается один раз и навсегда.** Хранится в `identity_mapping` (PK ключ → `gid`), а не выводится из графа, поэтому rebuild Neo4j даёт те же `gid` (`V2__identity_mapping.sql:1-2`, `IR/IdentityMapping.java:8-14`) 🟢. `UUID.randomUUID()` — недетерминированный генератор: «детерминированность» означает стабильность после первого назначения, не вычисление из ключа 🟡 (при потере таблицы `gid` не восстановить).
2. **Crosswalk без слияния.** `approve` берёт `gid` у любого из ключей (или создаёт новый) и присваивает второму; если оба уже имеют **разные** `gid` — `CrosswalkConflictException`, состояние не меняется (транзакция откатывается) (`PostgresIdentityMapping.java:103-122`; `IR/CrosswalkConflictException.java:3-6`). Повтор идемпотентен (`ON CONFLICT DO NOTHING`, `:137`) 🟢. Рассогласование, возникшее из-за гонки с параллельным `resolve` между чтением и вставкой, также ведёт к конфликту (`:113-116`). Обратной операции (разделить/удалить crosswalk) в модуле нет 🟡.
3. **Порядок пары.** `SourceKeys.compare` — по `source().name()`, затем `sourceType`, `sourceId` (лексикографически); комментарий в `V2` прямо оговаривает, что CHECK `left < right` не ставится, чтобы не зависеть от коллации БД (`IR/SourceKeys.java:10-16`, `V2__identity_mapping.sql:14`) 🟢.
4. **Признаки (`FeatureExtractor.extract`).** По типу узла события: `ComputeInstance` → `HOSTNAME`; `Repository` → `REPOSITORY_URL`; `ITSystem`, `Service`, `Team` → `NAME`; для `ITSystem`/`Service` дополнительно `OWNER` = `gid` конца связи `OWNED_BY`, если он уже разрешён в `gids`. Остальные метки признаков не дают; запись только со связью (нет `UpsertNode`) — пустой набор (`IR/FeatureExtractor.java:43-69`) 🟢. Сомнение: `OWNED_BY` строит только EAM (`EamMapper`), `Service` приходит из SCM, где `OWNED_BY` нет, поэтому `OWNER` практически достижим только для `ITSystem` 🟡.
5. **Нормализация значений** (точное равенство, нечёткости нет): hostname — trim, lower-case, без завершающих точек, FQDN целиком; URL репозитория — `host/path` в lower-case без схемы, `user@`, `.git`, `/` (scp-форма поддержана); имя — NFKC, lower-case, серии не-букв/цифр → один пробел (`IR/FeatureNormalizer.java:22-62`) 🟢. Пустой результат → признака нет. Это **вторая** реализация нормализации URL наряду с `GitUrls.normalize` (TODO в `normalizer/ScmMapper.java:103-104`): `GitUrls` сохраняет регистр пути, `FeatureNormalizer` приводит весь путь к lower-case 🟡.
6. **Оценка совпадения.** `FeatureKind`: `HOSTNAME 0.6`, `REPOSITORY_URL 0.8`, `NAME 0.4` — сильные; `OWNER 0.2` — только усиливает. `score = min(1.00, сумма весов различных видов)`, шкала 2, `HALF_UP` (`IR/FeatureKind.java:15-47`). Веса в коде, не в конфигурации 🟢.
7. **Поиск кандидатов (`record`).** Сортировка признаков → advisory-lock на `key|<ключ>` и на `<семейство>|<вид>|<значение>` для **сильных** признаков (детерминированный порядок исключает взаимную блокировку) → `DELETE`+`INSERT` признаков ключа (`identity_feature`) → для каждого сильного признака `FIND_OTHERS`: другие ключи того же `feature`+`label_family`+`value`, **не делящие `gid`** с данным (`NOT EXISTS … identity_mapping`), `ORDER BY`, `LIMIT 51` → слабые признаки добавляются к найденным парам, если у партнёра такой же `OWNER` → `upsertCandidate` с нормализованным порядком пары (`PostgresIdentityCandidates.java:78-102, 154-188, 190-239`) 🟢.
8. **Предохранитель MAX_MATCHES = 50.** Если у одного признака больше 50 совпадений, по нему кандидаты не создаются (WARN с количеством без значения), а `matched` у уже существующих пар при следующем upsert перезаписывается без него — «допущение PoC» в Javadoc класса (`:33, :212-216`) 🟢/🟡: пара может «потерять» совпавший признак и score.
9. **Идемпотентность кандидата.** `ON CONFLICT … DO UPDATE SET matched, score, updated_at`; `first_seen_at` и `status` не трогаются (`:54-61`); `status` по умолчанию `OPEN` 🟢. **Семейство меток**: подтипы `ComputeInstance` объединены (`IR/LabelFamily.java:10-12`), остальные метки сравниваются только сами с собой — кросс-метковых кандидатов нет 🟢.
10. **Утверждения свойств и конфликты (`assertProperties`).** Под advisory-lock'ом `conflict|<gid>`: `DELETE` утверждений записи → `INSERT` по каждому свойству с флагом `authoritative = matrix.isAuthoritative(label, property, source)` → `recompute(gid)` (`PostgresSourceConflicts.java:81-103`). Расходящаяся пара: неавторитетное утверждение `d` и **один** авторитетный `m` (если мастеров несколько — первый по `source, source_type, source_id`), `d.value <> m.value` (точное сравнение строк) (`:41-47`) → upsert `OPEN` (повторное открытие обнуляет `resolved_at`, сохраняет `opened_at`; `updated_at` меняется, только если изменилась пара значений или статус) (`:48-58`) → пары, ранее `OPEN` (`kind='PROPERTY'`), но больше не расходящиеся, → `RESOLVED` (`:59-63, 226-269`) 🟢.
11. **Конфликт связи (`assertRelation`).** Нет второй стороны или значения равны → закрыть открытые `RELATION`-строки этой записи и свойства; иначе upsert `OPEN` вида `RELATION` (`:105-119, 176-208`) 🟢. `retract` закрывает `RELATION`-конфликты, где запись — `dissent`, но **не** те, где она — мастер (`:121-131`, `RESOLVE_RELATION` фильтрует `dissent_*`, `:64-66`) 🟡.
12. **Что пишется в граф.** Ничего: модуль работает только с PostgreSQL; canonical-значение выбирает проектор (`SourceConflicts` Javadoc, `IR/SourceConflicts.java:13-17`) 🟢. `SourceConflict.toString()` не содержит значений источника (ловушка 11) (`IR/SourceConflict.java:77-81`) 🟢.

### Q3.3 Структуры данных

Таблицы `identity_mapping`, `approved_crosswalk` (V2), `identity_feature`, `identity_candidate` (V4), `property_assertion`, `source_conflict` (V5, + колонка `kind` в V7) — `data-dictionary.md` §Q.3. Записи `Crosswalk`, `Feature`, `IdentityCandidate`, `SourceConflict` — там же.

### Q3.4 Константы

`MAX_MATCHES=50` (`PostgresIdentityCandidates.java:33`); веса `FeatureKind` (`FeatureKind.java:15-18`); `hashtextextended(?, 0)` для advisory-lock'ов — ключи блокировок строковые: `key|…`, `<семейство>|<вид>|<значение>`, `conflict|<gid>` (хэш 64 бит, коллизия лишь лишняя сериализация) 🟡.

### Q3.5 Сомнения и расхождения

- 🔴 **`identity_candidate.status` никогда не меняется.** В коде нет ни одного `UPDATE identity_candidate SET status`; `approve` (crosswalk) не помечает кандидата подтверждённым, `CrosswalkService` вызывает только `identity.approve(...)` (`ingestion/ingestion-service/.../CrosswalkService.java:49`). `package-info` обещает «подтверждение остаётся за approved crosswalk» (`IR/package-info.java:11-12`), но связь «кандидат ↔ crosswalk» через `status` не реализована; консоль фильтрует по `status` (по умолчанию `OPEN`) (`access/admin-console/.../IdentityController.java:40`) — подтверждённые кандидаты останутся `OPEN` навсегда, пока их не отфильтрует общий `gid` в `FIND_OTHERS` (то есть они исчезнут из новых совпадений, но останутся в таблице).
- 🟡 Кандидаты ищутся только внутри одного семейства меток и только по равенству нормализованных значений (п.7, п.9). Условие `NOT f.source/type/id = self` не исключает пары внутри одного источника: два `ITSystem` EAM с одним именем тоже станут кандидатами. Есть ли в данных PoC реальные кросс-источниковые совпадения, из кода не видно — вопрос для Detective/R.3.
- 🟡 `UUID.randomUUID()` для `gid` (п.1); нет обратной операции для crosswalk (п.2); `OWNER` почти недостижим для `Service` (п.4); потеря признака при превышении `MAX_MATCHES` (п.8); `retract` не закрывает конфликты, где запись — мастер (п.11).
- 🟡 Тесты Postgres-реализаций вне модуля; в модуле только чистые функции.
- 🟡 Две реализации нормализации URL (п.5).

---

## Q4. `ingestion/graph-projector`

Сокращение: `GP` = `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector`.

**Назначение.** Ведёт событие журнала до конечного статуса (`EventProcessor`), пишет `GraphCommand`'ы в Neo4j одной транзакцией (`GraphProjector`), добирает отложенные связи (`PendingRelations`), обрабатывает missing set после `snapshot-complete` (`Reconciler`) и применяет схему Neo4j (`schema/Neo4jSchema`) 🟢.
Состав: 21 Java-файл main, ≈2700 строк (самые объёмные: `GraphProjector` 750 строк, `EventProcessor` 646, `PropertyJson` 263, `Reconciler` 221, `PendingRelations` 203). Тесты: 86 `@Test` в 7 классах (`EventProcessorTest` 46, `ProjectionRequestTest` 12, `ReconcilerTest` 12, `Neo4jSchemaTest` 8, `VersionDecisionTest` 4, `PropertyJsonTest` 3, smoke 1); **`GraphProjector` против реального Neo4j в модуле не тестируется** — покрывает `platform/integration-tests` (R.2e) 🟡.

### Q4.1 Поток управления

**`EventProcessor.process(event, raw)`** (`GP/EventProcessor.java:185-199`): читает строку журнала (нет — `IllegalArgumentException`); статусы `PROJECTED`, `SUPERSEDED`, `DUPLICATE`, `IGNORED_OLD_VERSION`, `QUARANTINED` возвращаются без изменений (`:99-105, 190-192`); далее маршрут по `type`:

| `type` | Ветка | Где |
|---|---|---|
| `architecture.asset.upserted.v1` | `upsert`: нормализация → gid → кандидаты → конфликты → запись | `:201-252` 🟢 |
| `architecture.asset.deleted.v1` | `delete`: tombstone без normalizer | `:254-287` 🟢 |
| `architecture.sync.snapshot-complete.v1` | `snapshotComplete`: в `ReconciliationTrigger`, граф не пишется | `:289-300` 🟢 |
| прочее | `QUARANTINED / UNSUPPORTED_EVENT_TYPE` | `:197` 🟢 |

Шаги `upsert` (в этом порядке): `sourceCode` → `decideBeforeWrite` (только из `RECEIVED`) → `VALIDATED` → `normalize` → warnings → `DeferRelation` для `UnresolvedReference` (с `distinct`) → `NORMALIZED` → `resolveGids` → `recordCandidates` → `ignoreIfOlderOnRetry` → `assertProperties` + `assertSystemRelation` + `assertSystemPlatform` → `RESOLVED` → `write` → `recheck*` → `PROJECTED`/`IGNORED_OLD_VERSION` + `saveCheckpoint` (`:201-252, 346-388, 612-617`) 🟢.

**Обработка ошибок** (`write`, `:354-373`) 🟢:

| Исключение | Исход | Код |
|---|---|---|
| `ProjectionException` | `RETRYING` | `e.code()` (`UNRESOLVED_ENDPOINT`) |
| `IllegalArgumentException` (инварианты команд) | `QUARANTINED` | `INVALID_COMMANDS` |
| `TransientException`, `ServiceUnavailableException`, `SessionExpiredException` | `RETRYING` | `GRAPH_UNAVAILABLE` |
| `ClientException` с кодом `Neo.ClientError.Statement.*` / `Request.*` | `QUARANTINED` | `GRAPH_REJECTED` |
| прочие `ClientException` (в т.ч. нарушение constraint, безопасность) | `RETRYING` | `GRAPH_CLIENT_ERROR` |
| прочие `Neo4jException` | `RETRYING` | `GRAPH_ERROR` |
| `IdentityStoreException` (кандидаты / конфликты) | `RETRYING` | `IDENTITY_CANDIDATE_FAILED` / `SOURCE_CONFLICT_FAILED` |
| любая ошибка триггера reconciliation | `RETRYING` | `RECONCILIATION_TRIGGER_FAILED` |
| нормализация | `QUARANTINED` | код из `Normalizer` |

Прочие `RuntimeException` (NPE normalizer'а, ошибки журнала, `IllegalStateException` перехода) **пробрасываются** вызывающему: событие остаётся в уже достигнутом статусе, повтор безопасен (Javadoc `:72-75`) 🟢. Причины всех веток — без значений из источника (`getSimpleName()` или код Neo4j).

**`GraphProjector.project`** (`GP/GraphProjector.java:106-111, 302-359`): `session.executeWrite(tx -> write(tx, request))` внутри `try-with-resources`; драйвер — один bean (ловушки 7, 8). Шаги `write`: (1) `MERGE (r:SourceRecord {source,sourceType,sourceId}) SET r._lock = true REMOVE r._lock` — блокировка записи до чтения версии (`:305-310`); (2) `VersionDecision` → `NOOP_SAME_VERSION`/`IGNORED_OLD_VERSION` без записи (`:312-324`); (3) `PendingRelations.deleteOf` — отложенные связи записи заменяются целиком (`:328`); (4) команды: `UpsertNode` (+ `healPending`, `healAliases`), `DeferRelation`, `TombstoneSourceRecord`, `UpsertRelation`, `CloseAssertion` (`:329-349`); (5) `closeReplacedRelations` (не для tombstone), `markRelationOnlyRecord`, `writeConflictMarkers`, `linkSyncRun` (`:350-357`) 🟢.

### Q4.2 Алгоритмы и встроенные правила

1. **Версия, а не `eventId`, решает идемпотентность проекции.** `VersionDecision` сравнивает `SourceVersion` как `BigInteger` (`99 < 184`) (`GP/VersionDecision.java:20-38`, `contracts/event-schemas/.../SourceVersion.java:10-21`): нет записи → `APPLY`; ниже → `OLD`; **равна → `SAME`**; выше → `APPLY`. Для tombstone равная версия применяется, если запись ещё активна (`:29-38`). `EventProcessor.decideBeforeWrite` переводит `SAME` в `DUPLICATE`, `OLD` — в `IGNORED_OLD_VERSION` ещё до нормализации, а проектор повторяет проверку в транзакции после блокировки (`EventProcessor.java:303-322`, `GraphProjector.java:312-324`). **Следствие для сомнения «`poll:` и `snap:` с одной версией, но разными `eventId`»:** второе событие получит `DUPLICATE` (в журнале этот статус **персистируется** — `EventProcessor.finish` → `transition(→DUPLICATE)`, в отличие от `PostgresEventJournal.append`, Q1.2 п.2), граф не меняется. Сомнение **снято**: проекция идемпотентна по версии 🟢. Остаточный риск 🟡: содержимое при равной версии не сравнивается (`contentHash` не участвует в решении) — если источник изменил объект без роста `ver`, изменение будет отброшено.
2. **Повтор из `RETRYING`.** `decideBeforeWrite` работает только из `RECEIVED`; для `RETRYING` действует `ignoreIfOlderOnRetry` — отсекает только `OLD`, равную версию пропускает дальше («запись идемпотентна, проектор вернёт `NOOP`») (`:329-344`) 🟢. 🟡 Порядок в `upsert`: `recordCandidates` (`:232`) и в `delete`: `candidates.forget` (`:271`) выполняются **до** `ignoreIfOlderOnRetry` (`:237`, `:275`): устаревшее `RETRYING`-событие успевает заменить признаки в `identity_feature` (V4) более старой версией; защита от этого стоит только перед записью конфликтов.
3. **Авторитетность.** Свойство узла пишется, только если `AuthorityMatrix.isAuthoritative(label, property, source)`; иначе имя уходит в `skippedProperties` (`Label.property`); связь — если источник авторитетен для типа, иначе в `skippedRelations` (`GraphProjector.java:417-432, 552-562`) 🟢. `null` не попадает в `props` (`NodeProperties.of`, `GP/NodeProperties.java:25-39`) — ловушка 5 соблюдена 🟢. Утверждение `ASSERTS` несёт `authority = MASTER|SUPPLEMENTARY` и `confidence = 1.0` (`:441, 461-462`).
4. **Узел и метки.** `MERGE (n:<root> {gid})` по **корневой** метке (подтипы `ComputeInstance` меняются без дубля при разных constraint'ах), подтип добавляется `SET n:<Label>`, прочие подтипы корня снимаются `REMOVE` (`:442-457, 729-745`) 🟢. `firstSeenAt` = минимум, `lastSeenAt` = максимум по `eventTime`; `isCurrent=true` и снятие `deletedAt` — только для мастера (`:458-460`) 🟢.
5. **`MERGE` только по ключам под constraint** (ловушка 4): `SourceRecord(source,sourceType,sourceId)`, `<Label>.gid`, `SourceSystem.code`, `SyncRun.runId`, `PendingRelation.key`; связи `OWNS_RECORD`, `ASSERTS`, `PROCESSED`, `DEFERS` и доменные `MERGE`-ятся только между уже найденными узлами (`:451-461, 585-589`; `PendingRelations.java:74-77`) 🟢. Identity-свойства темпоральной связи (`RelationType.identityProperties()`, сейчас `commit` у `DEPLOYED_FROM`) входят в шаблон `MERGE` ребра — смена даёт **новое** ребро (`:577-589, 649-660`).
6. **Cypher без конкатенации значений** (ловушка 1): значения только `$params`; в текст подставляются метки из `NodeLabel`, типы связей из `RelationType`, имена identity-свойств из enum (`:445-447, 578-589, 637, 659`) 🟢. Границ переменной длины пути нет (ловушка 2 неприменима).
7. **Темпоральные связи.** Связь «открывается» (`opening`), если нет `validFrom` или она закрыта: `validFrom` = из источника, иначе `eventTime`; открытая сохраняет `validFrom`; `validTo = $validTo` (обычно `null`) (`:566-575, 588`) 🟢. `closeReplacedRelations` для каждого типа, который версия утверждает (`UpsertRelation`) или откладывает (`DeferRelation`), закрывает открытые связи **этой же записи** (три поля `assertedBy*`), отсутствующие в наборе `(from.gid, to.gid, identity props)`: `validTo = max(validFrom, eventTime)`. Типы без утверждений в версии не трогаются; tombstone замещения не делает (`:611-647`) 🟢.
8. **Tombstone** (`:507-550`): запись `active=false`, `deletedAt`; закрываются все открытые темпоральные связи, утверждённые записью (через узел `ASSERTS` и отдельно — по `assertedBy*` для записей «только связь», по циклу по `RelationType.values()` без индекса); каноничный узел получает `isCurrent=false`, `deletedAt = coalesce(...)`, **только если нет другого активного `MASTER`-утверждения**. Физического удаления нет 🟢. Tombstone неизвестной записи создаёт «пустую» неактивную запись с версией и тем самым отсекает более ранние upsert'ы (`OLD`) 🟡.
9. **Отложенные связи** (`PendingRelations`). `DeferRelation` → узел `PendingRelation` (`key` = SHA-256 от длинно-префиксованных полей утверждающей записи, типа и обоих концов), свойства связи — JSON-строкой (`PropertyJson`: Neo4j не хранит map; JSON-библиотеку не подключают) (`PendingRelations.java:53-80, 174-197`). `healPending` после `UpsertNode` достраивает связи, где запись — конец и другой конец уже имеет активную запись с узлом нужной метки (`endpointGids`); `candidates` требует `r.active` и `r.sourceVersion = p.referrerVersion`, то есть отложенная связь устаревшей версии не достраивается (`:86-107`). `healAliases` подменяет конец-alias (`LookupKeys`) реальным ключом по правилам `ALIAS_RULES`: SCM `REPOSITORY.url` и EAM `IT_SYSTEM.eamCode` (`GraphProjector.java:92-95, 382-415`) 🟢. Допущение о конкурентности (однопоточный диспетчер + `AdminLock`) зафиксировано в Javadoc (`:62-67`) 🟡 — нарушается при втором экземпляре сервиса.
10. **Маркеры конфликтов.** `writeConflictMarkers` ставит `ASSERTS.conflicts` (список имён свойств) на рёбра записей из набора и снимает у остальных рёбер `gid`; значения не пишутся (`:471-491`) 🟢. `ConflictMarkers.none()` — «не трогать» (`GP/ConflictMarkers.java:24-63`).
11. **Конфликты и сверки в PostgreSQL до транзакции Neo4j**: `assertProperties` (текстовые значения непустых свойств), `assertSystemRelation` (`Deployment.eamSystemId` против системы EAM сервиса, `lookup.eamSystemOfService`), `assertSystemPlatform` (`ITSystem.platformId` против `Solution.platformId`, ADR 0031), `recheckServiceDeployments` и `recheckSolutionSystems` после записи сервиса/решения (`EventProcessor.java:394-561`). Граф они не меняют. 🟡 `assertProperties` заменяет утверждения записи **целиком по непустым свойствам** (`conflicts.assertProperties` делает delete+insert, `PostgresSourceConflicts.java:81-103`): свойство, пропущенное в частичной записи, исчезает из PG-утверждений, хотя в графе остаётся прежнее значение (п.3) — конфликт по нему «рассасывается» без изменения графа.
12. **`gid` выдаётся до транзакции.** `resolveGids` вызывает `identity.resolve(key)` для самой записи и `identity.find(endpoint)` для чужих концов; чужой конец без `gid` — `ProjectionException(UNRESOLVED_ENDPOINT)` → `RETRYING`, `gid` для чужих не создаётся (`:569-601`) 🟢. Внутри `executeWrite` обращений к PostgreSQL/S3 нет (ловушка 7) 🟢.
13. **Checkpoint.** `finish` вызывает `journal.saveCheckpoint("projector", source, eventId)` после перехода в конечный статус, то есть после коммита Neo4j; `RETRYING` и `QUARANTINED` его не сдвигают (`:612-617`; `retry`/`quarantine` `:619-627`) 🟢. 🟡 Курсор — `eventId` (не монотонная величина). В ingestion нет читателя checkpoint `projector` (`loadCheckpoint` вызывается только в `Poller` для собственных checkpoint'ов адаптеров, `adapters/adapter-core/.../Poller.java:93,181`); поведение диспетчера от него не зависит (идёт по статусам журнала, Q5). Читает его только `access/admin-console` (`ConsoleReadRepository.java:55`, R.2d) — для отображения. Поведение описано в `docs/02-data-model.md:275-286`.
14. **Reconciler (missing set).** Запускается только из `snapshotComplete` (ловушка 9) (`GP/Reconciler.java:88-144`): `snapshotContents(source, syncRunId)`; если событий в журнале меньше `objectCount` маркера — `IllegalStateException` (маркер → `RETRYING`); `touched = objectsReceivedSince(source, firstReceivedAt)` — объекты с любым событием после начала прогона не удаляются; для каждой активной записи источника вне прогона и вне `touched`: tombstone-событие `architecture.asset.deleted.v1` с `eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>` фиксируется в журнале и S3 **до** записи в граф, затем `retract` конфликтов и `project(Tombstone)` с **применённой версией записи**, затем строка журнала доводится до `PROJECTED`/`IGNORED_OLD_VERSION` (`:130-136, 156-194`) 🟢. Утверждение «tombstone с применённой версией не затирает свежее» держится на `VersionDecision.forTombstone` 🟢.
15. **Схема Neo4j** (`GP/schema/Neo4jSchema.java:41-75`): `IF NOT EXISTS` для UNIQUE `gid` по каждой канонической метке (имя вида `it_system_gid`), UNIQUE `SourceRecord(source,sourceType,sourceId)` (**composite uniqueness — доступно в Community**), `SourceSystem.code`, `SyncRun.runId`, `PendingRelation.key`, `Environment.code`; индекс `ITSystem.eamCode`; полнотекстовый `asset_text` по `ITSystem|Service` `name, description`. Каждый statement — отдельный `executeWrite`; ошибки не глушатся. Платные NODE KEY и existence constraints не используются 🟢. Что `IF NOT EXISTS` не пересоздаёт изменившееся определение — оговорено в Javadoc (`:25-26`).
16. **Миграция темпоральности.** `TemporalRelationMigration.migrate(now)` один раз при старте сервиса проставляет `validFrom = coalesce(min(firstSeenAt), now)` связям `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `DECOMPOSED_INTO`, `IMPLEMENTED_IN` без `validFrom` (ADR 0028); идемпотентна (`GP/TemporalRelationMigration.java:22-66`, вызов — `ingestion/ingestion-service/.../IngestionServiceConfiguration.java:72`) 🟢.

### Q4.3 Структуры данных

Записи и интерфейсы модуля (`ProjectionRequest`, `ProjectionResult`, `ProcessingResult`, `ConflictMarkers`, `GraphProjection.ActiveRecord/AppliedRecord`, `EamLookup.*`, `Reconciler.Report`) и то, что проектор пишет в Neo4j, — `data-dictionary.md` §Q.4.

### Q4.4 Константы

`CONSUMER="projector"` (`EventProcessor.java:84`); коды `IDENTITY_CANDIDATE_FAILED`, `SOURCE_CONFLICT_FAILED`, `GRAPH_UNAVAILABLE`, `GRAPH_REJECTED`, `GRAPH_CLIENT_ERROR`, `GRAPH_ERROR`, `INVALID_COMMANDS`, `UNKNOWN_SOURCE`, `UNKNOWN_SCHEMA_VERSION`, `UNSUPPORTED_EVENT_TYPE`, `RECONCILIATION_TRIGGER_FAILED`, `UNRESOLVED_ENDPOINT`; `TYPE_ASSET_DELETED`, `TYPE_SNAPSHOT_COMPLETE`, `SCHEMA_ASSET_DELETED` (`:86-94`); `Reconciler.EVENT_ID_PREFIX="reconcile:"` (`Reconciler.java:61`); `SYSTEM_PROPERTY="eamSystemId"`, `PLATFORM_PROPERTY="platformId"` — имена `RELATION`-конфликтов 🟢.

### Q4.5 Сомнения и расхождения

- 🟡 **Литералы `TYPE_ASSET_DELETED`, `TYPE_SNAPSHOT_COMPLETE`, `SCHEMA_ASSET_DELETED` (R.2a п.3, R.2b P1.6)** — подтверждено: в проекции объявлены пакетно-приватные строки (`EventProcessor.java:92-94`), в адаптерах — публичные константы (`adapters/adapter-core/.../EventMapper.java:29,32,41,42`), в диспетчере — ещё одна копия (`ingestion/ingestion-service/.../StoredEventReader.java:34`, Q5). `contracts/event-schemas` содержит только `TYPE_ASSET_UPSERTED`. Общий источник и тест на совпадение отсутствуют; зависимость `ingestion → adapters` запрещена `CLAUDE.md`, поэтому единственный корректный источник — `contracts/event-schemas` 🟡/🔴 (см. Q6).
- 🟡 Дублирование `sourceCode(urn)` — третья копия таблицы `urn:corp:{eam|scm|cmdb|deploymap}` (`EventProcessor.java:634-645`; Q2.5).
- 🔴/🟡 **`snapshot-complete` без `objectCount` отключает проверку полноты.** `objectCount()` возвращает `0`, если поле отсутствует или не число (`EventProcessor.java:629-631`), а `Reconciler` отвергает прогон только при `eventCount < objectCount` (`Reconciler.java:106`): маркер без счётчика проходит с любой неполной выборкой, и все активные записи источника вне прогона получат tombstone. Адаптеры всегда кладут `objectCount` (`adapters/adapter-core/.../EventMapper.java:94`), но проверка схемы маркера (`dataschema`) в проекции отсутствует (в отличие от `deleted`) 🟡.
- 🟡 Устаревшее `RETRYING`-событие пишет кандидатов до проверки версии (п.2).
- 🟡 PG-утверждения свойств расходятся с графом при частичной записи (п.11).
- 🟡 Checkpoint `projector` в ingestion только пишется; читает его лишь консоль (п.13).
- 🟡 `Reconciler.journalTombstone` ведёт строку по `ordinal()` перечисления: для строки в `QUARANTINED` (ординал 5) шаги пропускаются, `finishTombstone` затем пытается `QUARANTINED → PROJECTED` и получает `IllegalStateException` (`Reconciler.java:175-180, 190-193`; `ProcessingStatus.java:38`). Достижимо только при ручном карантине строки `reconcile:…` 🟡.
- 🟡 Raw tombstone `Reconciler.rawDelete` собран вручную как JSON и «совпадает с `InboxWriter.rawJson` для `DELETE`» только по комментарию (`Reconciler.java:196-201`); `sourceVersion` вставляется без кавычек — безопасно, пока `SourceVersion` проверяет `[0-9]{1,40}` 🟢.
- 🟡 `activeRecords` читает весь источник в память (комментарий `GraphProjector.java:146-147`); tombstone по типам связей — запросы без индекса (`:632`) — допущено для PoC.
- 🟡 `datetime == null → Instant.EPOCH` (R.2b) — влияние на проекцию (событие несёт `time = EPOCH`, а `eventTime` проекции = `event.time()`, `EventProcessor.java:358`): (а) `firstSeenAt` узла становится `1970-01-01` и уже не поднимается (минимум, `GraphProjector.java:458`); (б) темпоральная связь без `validFrom` из источника открывается с `validFrom = 1970-01-01` (`:574`); (в) при замещении в версии с `EPOCH` старая связь закрывается `validTo = max(validFrom, EPOCH) = validFrom`, то есть **нулевой длины** (`:643`). Версии и порядок событий не страдают (решает `SourceVersion`) 🟡. Подтверждено в Q6.
- 🟢 Модуль `access/*` не использует `graph-projector` и не получает writer-доступ — зависимости по `pom.xml` (`ingestion/graph-projector/pom.xml:18-39`: canonical-model, event-schemas, normalizer, identity-resolution, neo4j-java-driver).

---

## Q5. `ingestion/ingestion-service`

Сокращение: `IS` = `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice`.

**Назначение.** Единственное запускаемое Spring Boot-приложение группы: собирает компоненты ingestion (журнал, raw storage, identity, projector), крутит диспетчер журнала (`RECEIVED/RETRYING → EventProcessor`), даёт админский REST (`/admin/replay`, `/admin/rebuild`, `/admin/reconcile/{source}`, `/admin/crosswalks`), пишет аудит и метрики. Во внешний ingress не публикуется (`IS/IngestionServiceApplication.java:7`) 🟢.
Состав: 27 Java-файлов main, ≈1600 строк, + `application.yml` + миграции `V3__admin_audit.sql`, `V6__console_readonly_role.sql`. Тесты: 48 `@Test` в 7 классах (`JournalDispatcherTest` 9, `AdminControllerTest` 11, `IngestionMetricsTest` 11, `StoredEventReaderContractTest` 7, `AuthorizedPartyValidatorTest` 4, `AdapterControlClientTest` 3, `AdminOperationsTest` 3). `adapter-core` подключён со `scope=test` ради контрактного теста `StoredEventReader` против `EventMapper` адаптеров (`ingestion/ingestion-service/pom.xml:71-76`) — на схему зависимостей не влияет 🟢. Приложение целиком (`@SpringBootTest` + Testcontainers) в модуле не поднимается — это IT в `platform/integration-tests` (R.2e) 🟡.

### Q5.1 Поток управления

**Сборка** (`IS/IngestionServiceConfiguration.java`, `@Configuration`, явные `@Bean`, без component scan для доменных классов): `eventJournal` применяет миграции `JournalMigrations.apply` (`:45-49`; Flyway Boot не подключён, `:36-39`); `identityMapping`, `identityCandidates`, `sourceConflicts` зависят от него, чтобы миграции были применены (`:51-65`); `neo4jDriver` создаёт единственный `Driver` (writer-учётные данные), применяет `Neo4jSchema.apply` и `TemporalRelationMigration.migrate` (`:67-74`); `rawPayloadStore` создаёт клиент S3 и `ensureBucket()` (`:76-82`); `EventProcessor` получает `Normalizer.standard(projector::isActive, projector)` (то есть `ReferenceResolver` — это чтение Neo4j `isActive`, а `DeploymentLookup` — сам `GraphProjector`), `Reconciler` как `ReconciliationTrigger`, `metrics::recordWarnings` как `NormalizationWarnings` (`:100-112`); `JournalDispatcher` под `archrag.dispatcher.enabled` (`:121-135`) 🟢. Все бины с ресурсами закрываются (`destroyMethod = "close"`), сессии Neo4j открываются в `try-with-resources` на операцию (ловушка 8) 🟢.

**Диспетчер** (`IS/JournalDispatcher.java:94-163`, запуск — `IS/DispatcherRunner.java:31-52`): один поток `journal-dispatcher` (`scheduleWithFixedDelay`, пауза `poll-interval`, по умолчанию 1 с). Проход: `retryNotAfter = now − retryDelay` → цикл страниц: `AdminLock.tryAcquire()` (занят — проход закончен) → `reader.pending(after, retryNotAfter, batchSize)` → для каждого события: пропуск, если объект `(source,sourceType,sourceId)` уже в `blocked` → `process` → `metrics.recordEvent` → при итоге `null` или `RETRYING` объект добавляется в `blocked` → keyset по последнему элементу страницы до пустой страницы. `process`: нет `payloadRef` → `toDlq` с `NO_RAW_PAYLOAD` (для маркера — `MARKER_NO_RAW`); `rawStore.get(ref)` (ошибки хранилища идут наружу, событие остаётся прежним); `StoredEventReader.toEvent` (`IllegalArgumentException` → DLQ `INVALID_RAW_PAYLOAD`); `EventProcessor.process` (`:139-163`) 🟢.

**Админский REST** (`IS/AdminController.java`): все операции идут через `AdminOperations.run`: `AdminLock.acquire()` (занят → `AdminBusyException` → HTTP 409) → запись `STARTED` в `admin_audit` (ошибка записи — операция не начинается) → выполнение → итог `SUCCEEDED/FAILED` (при ошибке — только класс исключения, `Throwable` пробрасывается) (`IS/AdminOperations.java:38-61`) 🟢.

| Эндпоинт | Поведение | Где |
|---|---|---|
| `POST /admin/replay` `{source, receivedFrom?, receivedTo?, replayId?}` | проверка `source` по `[A-Za-z0-9:._-]{1,200}`, `from < to`, `replayId` — UUID; `ReplayService.replay` | `IS/AdminController.java:61-82` |
| `POST /admin/rebuild?confirm=true` | без `confirm=true` — 400; `RebuildService.rebuild` | `:84-92` |
| `POST /admin/reconcile/{source}` | `{urn:corp:<код> \| <код>}` → `AdapterControlClient.snapshot`; неизвестный — 404, адаптер недоступен/иной статус — 502; ответ адаптера (202/409) отдаётся как есть | `:101-115` |
| `POST /admin/crosswalks` `[{left,right,reason}]` (1..1000) | `CrosswalkService.load` под замком; аудит по элементам | `:117-123` |

**Replay** (`IS/ReplayService.java:63-100`): читает журнал страницами по 200 с `excludeReplays=true`; для каждой строки (кроме `snapshot-complete` → `SKIPPED_SNAPSHOT_MARKER` и строк без raw → `SKIPPED_NO_RAW`) восстанавливает событие из raw, создаёт **новую** строку журнала `eventId = replay:<replayId>:<исходный eventId>` с теми же `payloadRef`, `syncRunId`, `correlationId` и прогоняет через `EventProcessor`. Исходные строки не меняются. Повтор с тем же `replayId` идемпотентен (`append` → `DUPLICATE`) 🟢.
**Rebuild** (`IS/RebuildService.java:33-39`): `MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS` через `session.run` (неявная транзакция — иначе `CALL IN TRANSACTIONS` не работает) и `replay(null,null,null,newReplayId)`. Constraints/индексы, `identity_mapping`, crosswalk и checkpoint'ы адаптеров не затрагиваются → `gid` прежние 🟢.
**Crosswalk** (`IS/CrosswalkService.java:35-64`): для каждого элемента аудит `STARTED`; `approve`; исходы `APPLIED` (+`gid`), `CONFLICT` (`CrosswalkConflictException`), `INVALID` (`IllegalArgumentException`/`NullPointerException`, в т.ч. неизвестный `source` в `SourceSystemCode.valueOf`); остальные `RuntimeException` — аудит `FAILED` и проброс. `approvedBy` — `sub` токена, `approvedAt` — серверное время (в запросе не принимаются) 🟢.

### Q5.2 Алгоритмы и встроенные правила

1. **Монопольность записи.** Один PostgreSQL advisory lock на сессию (`pg_try_advisory_lock(0x41524348_41444D31)`) защищает и диспетчер (на каждую страницу), и админские операции; соединение удерживается до `Lease.close()` (`IS/AdminLock.java:19-86`). Это единственный механизм, делающий допущение «один писатель» из Javadoc `GraphProjector` (`GraphProjector.java:65-67`) исполнимым между экземплярами; внутри процесса диспетчер — один поток 🟢. Во время replay/rebuild диспетчер «спит» (проход заканчивается при занятом замке), поэтому его `RETRYING`-повторы не конкурируют с `replay:*`-строками 🟢.
2. **Порядок и блокировка объекта.** Порядок журнала `(received_at, source, event_id)`; после `RETRYING` или исключения более поздние события **того же объекта** в этом проходе пропускаются, чтобы не обгонять упавшее (`JournalDispatcher.java:113-121`) 🟢. Новый проход начинается с `after = null`, поэтому событие с более ранним `received_at`, ушедшее в `RETRYING`, подхватится в следующем проходе 🟢.
3. **Повторы без лимита.** `attempts` растёт только на `RETRYING` и нигде не сравнивается с порогом (единственные упоминания — `PostgresEventJournal.java:340,387`, `ConsoleReadRepository.java:313`); событие с вечной транзиентной причиной (например, `RECONCILIATION_TRIGGER_FAILED`) остаётся `RETRYING` и берётся заново каждые `retry-delay` (30 с) без эскалации в `QUARANTINED` 🟡. Для неожиданного исключения (`process` вернул `null`) статус и `updated_at` не меняются: `RECEIVED`-событие берётся **каждый проход** (по умолчанию раз в секунду) и пишет `LOG.error` (класс исключения); эскалации в DLQ нет 🟡.
4. **Raw payload.** Восстановление события из строки журнала и raw (`IS/StoredEventReader.java:44-86`): метаданные берутся из строки `inbox_event`, `payload`/`operation`/`completeness`/`updatedAt` — из raw JSON; `null`-поля payload отбрасываются, целые читаются как `Long`, `_completeness` кладётся в payload, у `DELETE` payload пуст; `updatedAt` обязателен и разбирается как `Instant`. Для маркера — отдельный формат `{syncRunId, objectCount, updatedAt}`, `objectCount` — целое ≥ 0, иначе `IllegalArgumentException` → DLQ `INVALID_RAW_PAYLOAD` (`:88-106`) 🟢. Raw недоверенный: сообщения без содержимого 🟢. Формат повторяет `EventMapper.toEvent` адаптеров; соответствие держит контрактный тест (`StoredEventReaderContractTest`) 🟢.
5. **Безопасность админки** (`IS/SecurityConfiguration.java:36-46`, `IS/AuthorizedPartyValidator.java`): stateless, CSRF выключен; `/admin/**` требует `SCOPE_architecture.admin`, всё остальное `denyAll()` (в т.ч. `/actuator`; в `application.yml` экспонируется только `health`, но фильтр его закрывает); JWT проверяется на issuer, подпись, срок, `aud` = `ARCHRAG_ADMIN_RESOURCE_URI` и `azp ∈ archrag.admin.allowed-clients` (по умолчанию `archrag-demo`; пустой список запрещён, `AdminProperties.java:15-19`). Консольный клиент в allow-list не входит: токен консоли с тем же scope отклоняется 401 🟢. Это закрывает ловушку 13 для единственной точки входа сервиса; отдельных tools здесь нет.
6. **Секреты.** Все значения — из env (`application.yml`, без умолчаний для PG/Neo4j/S3/OIDC); `Neo4jProperties.toString` и `S3Properties.toString` не печатают пароль/ключи (`IS/Neo4jProperties.java:9-12`, `IS/S3Properties.java:9-12`) 🟢 (ловушка 12). В аудит попадают параметры запроса и счётчики, не payload; `reason` crosswalk усекается до 200 символов, `error` — до 200 (`AdminAudit.java:22,66-68`, `CrosswalkService.java:70-78`) 🟢.
7. **Ошибки админки.** `BadRequestException` → 400, `AdminBusyException` → 409, `AdapterUnavailableException` → 502 (сообщения без значений запроса и адреса адаптера) (`AdminController.java:125-138`); прочие исключения — стандартный 500 Spring, отдельного обработчика нет 🟡.
8. **Метрики** (`IS/IngestionMetrics.java`): счётчик `archrag.ingestion.events{source,status}` (все пары предрегистрируются нулём), gauges `archrag.ingestion.sync.lag` (возраст старейшего ожидающего события источника по `received_at`; ожидающие = `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, RETRYING`) и `archrag.ingestion.dlq.open` (`dlq_entry WHERE replayed_at IS NULL`), обновляются `@Scheduled` раз в 15 с; счётчик `archrag.normalizer.unmapped.values{source,property}` — только для кода `UNMAPPED_CRITICALITY`. Теги ограничены перечислениями (кардинальность) (`:39-167`) 🟢. 🔴 `dlq.open` не уменьшается никогда, пока `replayed_at` не заполняется (Q1.5).
9. **Конфигурация диспетчера** (`DispatcherProperties`, record + `@DefaultValue`): `enabled=true`, `pollInterval=1s` (> 0), `batchSize=100` (1..1000), `retryDelay=30s` (≥ 0), `shutdownTimeout=30s` (`IS/DispatcherProperties.java:24-34`) 🟢. Остановка: `dispatcher.stop()` ставит флаг, `executor.shutdown()` и ждёт `shutdownTimeout`, потом `shutdownNow()` (`IS/DispatcherRunner.java:54-71`) 🟢.
10. **Миграции схемы.** `V3__admin_audit.sql` (таблица `admin_audit`: `operation`, `actor`, `request jsonb`, `replay_id`, `status`, `result jsonb`, `error`, `started_at`, `finished_at`; индексы по `started_at` и `replay_id`); `V6__console_readonly_role.sql` (идемпотентно создаёт кластерную роль `archrag_console_ro LOGIN` и выдаёт `SELECT` на `inbox_event, consumer_checkpoint, dlq_entry, identity_candidate, source_conflict, admin_audit`; пароль не задаётся; нужна привилегия `CREATEROLE`) 🟢. Доступ read-only консоли обеспечивается на уровне БД, а не кодом консоли 🟢.

### Q5.3 Структуры данных

`admin_audit`, `ReplayRequest`/`ReplayResult`/`CrosswalkItem`/`CrosswalkResult`, настройки (`DispatcherProperties`, `AdminProperties`, `AdaptersProperties`, `Neo4jProperties`, `S3Properties`) — `data-dictionary.md` §Q.5.

### Q5.4 Константы

`MARKER_NO_RAW`, `NO_RAW_PAYLOAD`, `INVALID_RAW_PAYLOAD` (`JournalDispatcher.java:47-49`); `SKIPPED_SNAPSHOT_MARKER`, `SKIPPED_NO_RAW`, `PAGE_SIZE=200` (`ReplayService.java:35-38`); `MAX_CROSSWALK_ITEMS=1000` (`AdminController.java:31`); `ADMIN_SCOPE="architecture.admin"` (`SecurityConfiguration.java:28`); `AdapterControlClient.TIMEOUT=10 мин` (`:20`); `WIPE` (`RebuildService.java:18`); ключ замка `0x41524348_41444D31` (`AdminLock.java:19`); литералы `TYPE_SNAPSHOT_COMPLETE` (`StoredEventReader.java:34`, `ReplayService.java:37`) 🟢.

### Q5.5 Сомнения и расхождения

- 🔴 `dlq_entry.replayed_at` не заполняется ни replay'ем, ни чем-либо ещё (`ReplayService` создаёт новые строки `replay:*` и не трогает DLQ, `ReplayService.java:78-100`): метрика `dlq.open` и фильтр консоли «открытые» всегда включают всё когда-либо отправленное в DLQ. Уточнить у архитектора, считается ли DLQ историей или очередью.
- 🟡 Нет лимита повторов и эскалации «ядовитых» событий (п.3).
- 🟡 Литерал `TYPE_SNAPSHOT_COMPLETE` продублирован в `StoredEventReader.java:34` и `ReplayService.java:37` (плюс `EventProcessor.java:93`, `EventMapper.java:32`); литералы `deleted` в сервисе не используются: тип берётся из строки журнала.
- 🟡 `DispatcherRunner.stop()` вызывает `dispatcher.stop()`, который выставляет `stopped = true` и никогда не сбрасывает (`JournalDispatcher.java:63,89-91`): повторный `start()` того же бина (перезапуск lifecycle без пересоздания контекста) не обработает ни одного события. В Boot-приложении не воспроизводится, пока контекст не перезапускают.
- 🟡 Rebuild удаляет **все** узлы базы Neo4j, а не только проекцию ingestion (`MATCH (n)`): при общей БД с чужими данными они будут стёрты. Защита — флаг `confirm=true`, аудит и scope (`RebuildService.java:18`, `AdminController.java:84-92`). Прерванный rebuild оставляет граф неполным (Javadoc `RebuildService.java:9-12`).
- 🟡 `POST /admin/reconcile/{source}` вызывает `/control/snapshot` адаптера без аутентификации (R.2b: эндпоинт открыт во внутренней сети); синхронный вызов с таймаутом 10 минут держит админский замок на всё время snapshot (`AdapterControlClient.java:19-20,56-72`, `AdminOperations.java:45`) — диспетчер в это время не работает 🟡.
- 🟡 Конфликт `CONFLICT` crosswalk не обновляет `identity_candidate.status` (Q3.5); успешный crosswalk меняет `gid` только для будущих событий — «узлы графа под новый `gid` переписывает следующий replay или rebuild» (Javadoc `CrosswalkService.java:17-20`) 🟡.
- 🟡 V6 требует `CREATEROLE` у пользователя приложения; без него миграция падает и сервис не стартует (комментарий в `V6__console_readonly_role.sql:3`).
- 🟡 Все `AuthorityMatrix.defaults()` создаются дважды (для `SourceConflicts` и `GraphProjector`) — одинаковые, но не общий экземпляр (`IngestionServiceConfiguration.java:62-65,84-87`).

---

## Q6. Сомнения R.2a / R.2b, отнесённые к `ingestion/`, — результат разбора

### Q6.1 п.1 — ручное сопоставление кодов источников — **подтверждено, 🟡**

Таблица «код источника ↔ `SourceSystemCode`/URN» существует в четырёх независимых копиях:
1. адаптер: `urn:corp:<код>` из `SourceSystem` (`adapters/adapter-core/.../EventMapper.java:47-49`);
2. `Normalizer.sourceCode` — `switch` `eam|scm|cmdb|deploymap` (`ingestion/normalizer/.../Normalizer.java:92-103`);
3. `EventProcessor.sourceCode` — тот же `switch` (`ingestion/graph-projector/.../EventProcessor.java:633-646`); его же использует `Reconciler` (`Reconciler.java:101`);
4. `IngestionMetrics.sourceTag` / `AdapterControlClient.code` — срез префикса `urn:corp:` и проверка по `SourceSystem.values()` (`IngestionMetrics.java:149-154`, `AdapterControlClient.java:33-35`).
Все копии сейчас согласованы; `asset`-адаптер публикует источник `cmdb` (имя адаптера ≠ код источника), поэтому в каждой копии `cmdb`, а не `asset`. Теста, который перебирает все `SourceSystemCode` и проверяет согласованность копий, нет. Добавление пятого источника потребует правки не менее трёх мест; пропуск в `Normalizer`/`EventProcessor` проявится как `QUARANTINED / UNKNOWN_SOURCE` только в рантайме.

### Q6.2 п.3 — литералы `TYPE_ASSET_DELETED`, `snapshot-complete.v1` и удаление/snapshot — **подтверждено, 🟡/🔴**

- Определения: `EventProcessor.java:92-94` (пакетно-приватные), `StoredEventReader.java:34`, `ReplayService.java:37` (только `snapshot-complete`), адаптеры — `EventMapper.java:29,32,41,42` (публичные). `contracts/event-schemas` содержит только `TYPE_ASSET_UPSERTED` (`CanonicalEvent`). Запрет `ingestion → adapters` в `CLAUDE.md` исключает ссылку на константы адаптера; единственный допустимый общий дом — `contracts/event-schemas`. Теста на совпадение копий между адаптерами и проекцией нет, кроме контрактного `StoredEventReaderContractTest`, который сверяет формат raw, а не строки типов 🟡.
- **Как обрабатывается удаление.** Тип `architecture.asset.deleted.v1` → `EventProcessor.delete`: требует `dataschema == urn:corp:schema:asset-deleted:1`, версия сравнивается по `VersionDecision.forTombstone`, граф: `active=false`, закрытие темпоральных связей, архивирование узла без других активных мастеров, **без физического удаления** (`EventProcessor.java:254-287`, `GraphProjector.java:507-550`). Источники удалений: (а) событие `DELETE` от адаптера (webhook), (б) `Reconciler` по missing set (синтетическое `reconcile:…`, фиксируется в журнале до записи в граф — `Reconciler.java:156-182`) 🟢.
- **Как обрабатывается завершение snapshot.** Маркер `snapshot-complete:<syncRunId>` — не проецируется; `EventProcessor.snapshotComplete` проводит его по статусам и вызывает `Reconciler`; сбой → `RETRYING / RECONCILIATION_TRIGGER_FAILED` (`EventProcessor.java:289-300`). Маркер без raw → DLQ `MARKER_NO_RAW` (восстановление — `POST /admin/reconcile/{source}`) (`JournalDispatcher.java:144-145`). Replay маркеры **пропускает** (`SKIPPED_SNAPSHOT_MARKER`), поэтому удаления при replay/rebuild воспроизводятся только через сохранённые события `reconcile:…` 🟢. Ловушка 9 соблюдена: удалений до маркера нет 🟢.
- 🔴 `dataschema` маркера не проверяется (в отличие от `deleted`); проверка полноты прогона отключается, если у маркера нет числа `objectCount` (Q4.5).

### Q6.3 События `poll:` и `snap:` с одной версией, но разными `eventId` — **снято (идемпотентно по версии), 🟢 с оговоркой 🟡**

Дедупликация по `source + eventId` (`uq_inbox_event`) пропустит оба события в журнал как разные. Далее `EventProcessor.decideBeforeWrite` сравнивает `SourceVersion` с применённой в графе: равная версия → `DUPLICATE` (до нормализации), ниже → `IGNORED_OLD_VERSION`; в транзакции Neo4j проверка повторяется под блокировкой записи и даёт `NOOP_SAME_VERSION` (`EventProcessor.java:303-322`, `GraphProjector.java:305-324`). Проекция идемпотентна по версии объекта, а не по `eventId`. Оговорка: содержимое при равной версии не сравнивается — `contentHash` в решении не участвует, поэтому изменение без роста `ver` отбрасывается (Q4.2 п.1). Что именно EAM считает `ver`, — вопрос к `docs/06-eam-integration.md`/R.3.

### Q6.4 Вложенные `null` в `attrs` EAM — **снято (не падает, не затирает), 🟢; побочный 🟡**

`Payload.optionalObject` копирует вложенную карту, допуская `null`-значения; `optional`/`optionalIdRef` читают `null` как «не передано» (`Payload.java:30-61, 113-122`). Для ArchObject это значит: `name: null` → `MISSING_REQUIRED_FIELD`; необязательные поля `null` → `null` в record → не пишутся в граф (`NodeProperties.of`); ссылки `platform_id`/`product_id` = `null` → связь не строится и существующая **не закрывается** (`EamMapper.java:208-214`; закрытие делает только `closeReplacedRelations` для типов, которые версия утверждает, `GraphProjector.java:611-647`). Побочный эффект: нет способа «явно удалить» значение — это соответствует ловушке 5 (нет явной семантики удаления). Ещё: `attrs: null` у `IT_SYSTEM` уходит в плоскую ветку и даёт вводящую в заблуждение причину `required field is missing: name` (Q2.5).

### Q6.5 `datetime == null → Instant.EPOCH` — **влияние на проекцию: 🟡**

Адаптер подставляет `EPOCH`, если у ArchObject нет `datetime` (`adapters/eam-adapter/.../EamApiConnector.java:181`). Дальше значение становится `event.time()` и `SourceRecord.fetchedAt`, а проекция использует `event.time()` как `eventTime` (`EventProcessor.java:358`). Последствия (подробно — Q4.5): `firstSeenAt = 1970-01-01`, `validFrom` связей без собственного `validFrom` = `1970-01-01`, замещение связей при `eventTime = EPOCH` закрывает старые связи интервалом нулевой длины. **Версии не затрагиваются**: порядок решает `SourceVersion`, а не время. Решение «что делать с отсутствующим `datetime`» — за архитектором (вариант: брать время приёма из журнала `received_at`).

### Q6.6 Новые вопросы, найденные в `ingestion/`

| № | Метка | Суть | Где |
|---|---|---|---|
| Н1 | 🔴 | `dlq_entry.replayed_at` нигде не заполняется (колонка и фильтр `replayed` описаны в `docs/02-data-model.md:313` и `docs/03-interfaces.md:413`, семантика «когда ставится» не описана) → метрика `dlq.open` и «открытые» DLQ в консоли не отражают состояние | Q1.5, Q5.5 |
| Н2 | 🔴 | `identity_candidate.status` не меняется ни crosswalk'ом, ни чем-либо ещё → подтверждённые кандидаты остаются `OPEN`; `docs/02-data-model.md:360` признаёт «ручных решений нет» (E3, UNLOCKER-174/175), то есть это известный пробел, но не отражено в Javadoc | Q3.5 |
| Н3 | 🔴 | Документ называет выход из `QUARANTINED` «решением оператора» (`docs/02-data-model.md:241,270`), но ни админский REST, ни другой код не вызывает `QUARANTINED → RETRYING`; также нигде не вызывается `PROJECTED → SUPERSEDED` (`ProcessingStatus.java:38-39`; grep по `.transition(` — только `EventProcessor` и `Reconciler`). Практический выход — replay новой строкой | Q5 |
| Н12 | 🔴 | `docs/02-data-model.md:309` утверждает «Таблиц `identity_candidate` и `conflict` нет… candidate matching не реализован», тогда как код содержит V4/V5/V7, `PostgresIdentityCandidates`, `PostgresSourceConflicts`, а в `docs/02-data-model.md:360` те же таблицы названы существующими. Документ противоречит сам себе и коду; правка docs — вне этапа | Q3 |
| Н4 | 🔴 | `snapshot-complete` без `objectCount` отключает проверку полноты и разрешает удаление всего missing set | Q4.5 |
| Н5 | 🟡 | Нет лимита повторов `RETRYING` и эскалации «ядовитых» событий | Q5.2 п.3 |
| Н6 | 🟡 | Устаревшее `RETRYING`-событие обновляет `identity_feature` до проверки версии | Q4.2 п.2 |
| Н7 | 🟡 | Checkpoint `projector` записывается ingestion'ом, но читается только `access/admin-console` (для отображения); на поведение обработки не влияет | Q4.2 п.13 |
| Н8 | 🟡 | Дублирование типов событий/таблицы кодов источников; нет теста согласованности | Q6.1, Q6.2 |
| Н9 | 🟡 | Тело дубля `(source, eventId)` и содержимое при равной версии не сравниваются | Q1.2 п.1, Q4.2 п.1 |
| Н10 | 🟡 | Rebuild стирает все узлы Neo4j (`MATCH (n)`) | Q5.5 |
| Н11 | 🟡 | Реализации Postgres/Neo4j в модулях не покрыты тестами самих модулей (только IT платформы) — проверить в R.2e | Q1, Q3, Q4 |

---

## Q7. Итоги по модулям R.2c

| Модуль | Java-файлов main / строк | Тестов | Ключевые алгоритмы | Основные риски (см. §) |
|---|---|---|---|---|
| `event-journal` | 5 (+1 SQL) / 550 | 2 в 1 классе | идемпотентный append, переходы под `FOR UPDATE`, `pending`, DLQ, S3 по hash | Q1.5: `replayed_at`, затирание ошибок, `DUPLICATE` не персистируется в `append` |
| `normalizer` | 15 / ≈1200 | 71 в 5 классах | `Payload` («не передано»), `CommandSink`, отложенные ссылки, привязка Deployment | Q2.5: копии таблицы кодов, два словаря критичности, `attrs: null` |
| `identity-resolution` | 19 / ≈1300 | 20 в 4 классах | gid в PG, crosswalk без слияния, признаки/кандидаты, конфликты по authority | Q3.5: `status` кандидата, `retract` мастера, потеря признака при > 50 |
| `graph-projector` | 21 / ≈2700 | 86 в 7 классах | версия-решатель, MERGE по ключам, замещение темпоральных связей, tombstone, pending/alias, Reconciler | Q4.5: `objectCount`, EPOCH, порядок candidates/stale, checkpoint write-only |
| `ingestion-service` | 27 / ≈1600 | 48 в 7 классах | диспетчер, `AdminLock`, replay/rebuild, crosswalk, аудит, метрики, безопасность | Q5.5: DLQ, повторы без лимита, rebuild стирает всё |

Машины состояний ingestion: `ProcessingStatus` (`flowcharts/ingestion-service.md`, «Жизненный цикл `inbox_event`»), `source_conflict` (`flowcharts/identity-resolution.md`), версия записи (`flowcharts/graph-projector.md`). `UnresolvedReference` → `DeferRelation` → `PendingRelation` → достроенная связь (Q2.2 п.6, Q4.2 п.9). Tombstone — Q4.2 п.8. Описание модели и ADR не дублируется: `docs/02-data-model.md`, `docs/adr/`.

---

# R.2d — `access/*` (UNLOCKER-266)

> Archaeologist, этап R.2d. Разделы X1–X4 — по одному на модуль группы `access/`; X5 — разбор сомнений R.2a–R.2c, относящихся к `access/`; X6 — итоги.
> Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Ссылки `файл:строка` — от корня репозитория. Skill `reversa-archaeologist` v1.1.0 (установленный runtime-экземпляр).
> Не разбирается: `platform/*` (R.2e), полная схема Neo4j и Flyway (R.3), сторонние библиотеки `admin-ui`.

## X1. `access/graph-query-core`

Сокращения: `GQC` = `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore`, `GQT` = `GQC/templates`.

**Назначение.** Единственный путь чтения графа для `mcp-server` и `admin-console`: реестр именованных Cypher-шаблонов, бюджет запроса и исполнитель `GraphQueryExecutor`, который принимает **ID шаблона**, а не текст Cypher (`GQC/GraphQueryExecutor.java:23-31,60-64`) 🟢. Зависит только от `canonical-model`, Neo4j Java Driver и Jackson 3 (подсчёт байт строки) (`access/graph-query-core/pom.xml:14-26`); `ingestion/*` и `adapters/*` не подключены, writer-учётных данных у модуля нет — они приходят из вызывающего приложения как готовый `Driver` 🟢. Состав: 14 Java-файлов main (+2 `package-info`), ≈1270 строк; тестов 47 `@Test` в 8 классах (`BudgetedCollectorTest` 6, `QueryTemplateRegistryTest` 13, `ResultBudgetTest` 3, `FulltextQueryTest` 3, `AssetTemplatesTest` 3, `ConsoleTemplatesTest` 4, `DependencyTemplatesTest` 9, `RuntimeTemplatesTest` 6). **`GraphQueryExecutor` в самом модуле не тестируется** — только IT `platform/integration-tests/.../GraphQueryCoreIT.java` (R.2e) 🟡.

### X1.1 Поток управления

`GraphQueryExecutor.execute(templateId, params, budget)` (`GQC/GraphQueryExecutor.java:60-97`) 🟢:
1. `registry.find(id)`, иначе `IllegalArgumentException("Unknown template")` (`:61-64`).
2. `checkParameters`: набор ключей `params` должен **в точности** совпасть с `template.parameters()`; лишний или недостающий ключ → `IllegalArgumentException` (`:133-146`). Параметр `limit` вызывающему недоступен: он зарезервирован (`QueryTemplate.java:53-56`) и добавляется исполнителем (`:72`).
3. Бюджет: запрошенный (или потолки целиком, если `null`) режется `clampTo(limits)` до потолков конфигурации (`:67`, `ResultBudget.java:30-38`).
4. `template.render(effective.maxDepth(), limits.maxDepth())` — подстановка `{maxDepth}`, единственная конкатенация в текст (`:68`, `QueryTemplate.java:133-138`).
5. `maxRows = kind.maxRows(effective)`: `NODES` → `maxNodes`, `PATHS` → `maxPaths` (`ResultKind.java:11-13`); в Cypher уходит `LIMIT maxRows + 1` (`:72`), чтобы отличить «ровно maxRows» от «обрезано».
6. `verifyReadOnly`: при первом исполнении шаблона выполняется `EXPLAIN <cypher>` в read-сессии, `queryType() == READ_ONLY` → вердикт `true` кэшируется в `ConcurrentHashMap` по ID шаблона; `false` кэшируется тоже и закрывает шаблон до перезапуска (`:99-124`). Ошибка планирования вердикта не создаёт и не кэшируется (`:120-123`).
7. `driver.session(READ_SESSION_CONFIG)` (try-with-resources) → `session.executeRead(tx -> collector.collect(new RowIterator(tx.run(cypher, queryParams))), TransactionConfig.withTimeout(effective.timeout()))` (`:78-87`). Тело транзакции повторяемо: внутри только чтение Neo4j и накопление в локальных списках (`:79-80`); `BudgetedCollector` без состояния между вызовами `collect` 🟢.
8. `Neo4jException` с кодом, содержащим `TransactionTimedOut`, превращается в `QueryTimeoutException(templateId)`; остальные пробрасываются как есть (`:94-96,126-131`).

`BudgetedCollector.collect` (`GQC/BudgetedCollector.java:41-58`): читает из итератора не более `maxRows + 1` элементов; `(maxRows+1)`-я строка → `truncated=true` без добавления; после добавления каждой строки накопленные байты (`JsonMapper.writeValueAsBytes(row).length`, `:60-66`) сверяются с `maxBytes`, строка, которая **не влезла**, отбрасывается и ставит `truncated=true` (`:52-54`). Если уже первая строка больше `maxBytes`, результат пуст и `truncated=true` 🟡 (вызывающий видит «пусто, обрезано»).

`RowIterator.next` + `check` (`GQC/GraphQueryExecutor.java:162-188`): значения читаются `asObject()`; `Node`, `Relationship`, `Path` — в том числе внутри `Map` и `List` — дают `IllegalStateException("… in result is forbidden: return projections")`. Шаблоны обязаны возвращать проекции (`n {.gid, …}`, скаляры, map) 🟢. Исключение не `Neo4jException`, поэтому драйвер его не повторяет и `translate` не вызывается.

### X1.2 Алгоритмы и встроенные правила

1. **Статическая валидация шаблона** в конструкторе `QueryTemplate` (`GQC/QueryTemplate.java:42-105`) 🟢:
   - текст оканчивается `LIMIT $limit` (`ENDS_WITH_LIMIT`, `:32-33,74-76`);
   - множество `$param` в тексте (минус `limit`) равно объявленному `parameters` (`:77-86`);
   - из `{…}`-плейсхолдеров допустим только `{maxDepth}`, не более одного (`:61-73`);
   - каждый variable-length путь `[..*..]` обязан иметь верхнюю границу — число или `{maxDepth}`; `*`, `*1..` отвергаются (`VAR_LENGTH`/`BOUNDED`, `:38-40,87-99`);
   - `{maxDepth}` допустим **только** как верхняя граница такого пути: число использований `{maxDepth}` как границы должно равняться числу плейсхолдеров (`:100-104`). Это реализует ловушки 1–2 из `CLAUDE.md`.
2. **Реестр** `QueryTemplateRegistry.of` проверяет потолок: литеральная глубина любого шаблона не выше `limits.maxDepth` и ID уникальны (`GQC/QueryTemplateRegistry.java:32-45`) 🟢. Реестр иммутабелен (`Map.copyOf`, `:25-27`).
3. **Бюджет**: `ResultBudget` отвергает неположительные значения и нулевой/отрицательный `timeout` (`GQC/ResultBudget.java:20-27`); `QueryLimits` создаёт `ResultBudget` в компактном конструкторе, чтобы потолки подчинялись тем же инвариантам (`QueryLimits.java:17-20`). `clampTo` берёт минимум по каждому полю, таймаут — меньший из двух (`ResultBudget.java:30-38`) 🟢. `maxPaths` — потолок строк для шаблонов `PATHS`; Javadoc называет его «резервируемым для шаблонов обхода» (`ResultBudget.java:11`), фактически он применяется (`ResultKind.maxRows`) 🟢.
4. **Read-only защита в три слоя** 🟢: (а) статика шаблонов + allowlist меток/типов связей; (б) `EXPLAIN → READ_ONLY` один раз на шаблон; (в) `AccessMode.READ` и `executeRead` (`GQC/GraphQueryExecutor.java:35-36,82`). Слой (в) **проверен как серверный отказ**: `GraphQueryCoreIT.readSessionConfigRejectsWritesAtDatabaseLevel` ждёт `Neo.ClientError.Statement.AccessMode` на `CREATE` в read-транзакции под пользователем без ролей (`platform/integration-tests/.../GraphQueryCoreIT.java:111-120`); `writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed` доказывает слой (б) (`:78-100`).
5. **Allowlist меток и типов связей** подставляется в текст из констант классов, не из ввода: `AssetTemplates.SEARCHABLE_TYPES` (7 меток, `GQT/AssetTemplates.java:16-17`), `RELATION_TYPES` (8 типов, `:102-103`), `DependencyTemplates.TRACE_LABELS` (11 меток, `GQT/DependencyTemplates.java:22-25`), `TRACE_RELATION_TYPES` (7, `:18-19`), `IMPACT_RELATION_TYPES` (5, `:98-99`), `ConsoleTemplates.STATS_LABELS` (= `TRACE_LABELS` + `SourceRecord`, `:19-25`), `STATS_RELATION_TYPES` (= `RELATION_TYPES` + `HOSTED_ON`, `ASSERTS`, `:28-35`), `NEIGHBORHOOD_RELATION_TYPES` (= `RELATION_TYPES` + `HOSTED_ON`, `:38`). Значения, приходящие от вызывающего (`relationTypes`, `relTypes`, `types`), передаются параметрами и сверяются вызывающим по этим спискам (Javadoc `DependencyTemplates.java:11-13`); сам `graph-query-core` членство **не проверяет** 🟡 (проверка в `TraceDependenciesTool`, X2, и `GraphController`, X3).
6. **Свежесть и темпоральность в запросах.** Открытая связь = `validTo IS NULL`; текущий узел = `isCurrent = true` — фильтры внутри шаблонов (`DependencyTemplates.java:29-33`, `RuntimeTemplates.java:33-41`, `ConsoleTemplates.java:115-116,145`). Исключения: `get_asset` и `explain_provenance` отдают закрытые узлы (`GQT/AssetTemplates.java:107-110,175-178`).
7. **`FulltextQuery.escape`** (`GQT/FulltextQuery.java:25-40`): нижний регистр (`Locale.ROOT`), экранирование 19 спецсимволов Lucene обратным слешем, `null`, если после очистки нет ни одной буквы/цифры. Приведение к нижнему регистру отключает операторы `AND/OR/NOT` 🟢. Результат идёт параметром `$text` в `db.index.fulltext.queryNodes('asset_text', t)` (`AssetTemplates.java:52`) — имя индекса `asset_text` создаётся в `ingestion/graph-projector` (R.3) 🟡.

### X1.3 Реестр шаблонов (14 шаблонов)

Список не дублирует Cypher; указаны параметры, возврат и особенности. Полная схема меток/свойств графа — R.3.

| ID шаблона | Параметры | `ResultKind` | Строка результата | Где |
|---|---|---|---|---|
| `search_assets` | `query`, `text`, `types`, `environment` | NODES | `gid, type, name, score, matchType (EXACT\|FULLTEXT), sources[], lastSeenAt`; только `isCurrent`; точные совпадения по `gid`, `SourceRecord.sourceId`, `ITSystem.eamCode` со score 1000, затем fulltext | `AssetTemplates.java:37-96` |
| `get_asset` | `gid` | NODES | `gid, type, properties (properties(n)), firstSeenAt, lastSeenAt, deletedAt, isCurrent, sources[]` (с неактивными записями) | `:112-141` |
| `asset_relations` | `gid` | NODES | действующие связи 1 уровня в обе стороны: `relationType, direction, gid, type, name, isCurrent, validFrom` | `:150-168` |
| `explain_provenance` | `gid` | NODES | `gid, type, isCurrent, deletedAt, records[]` (`contentHash`, `authority`, `confidence`, `conflicts`) без значений свойств | `:180-210` |
| `trace_downstream` / `trace_upstream` | `gid`, `relationTypes` | PATHS | путь: `nodes[]` (c `conflicts`), `relations[]` (c `assertedBy*`, `sourceFetchedAt`, `sourceActive`); `[*1..{maxDepth}]` по/против стрелок | `DependencyTemplates.java:82-95` |
| `trace_impact` | `gid`, `relationTypes`, `environment` | PATHS | как `trace_upstream`, но путь заканчивается на `Service`/`ITSystem`; фильтр окружения по каждому `Deployment` пути | `:112-123` |
| `runtime_footprint` | `systemGid`, `environment` | NODES | строка на (service, deployment, compute): `system, service, deployment, compute, host` | `RuntimeTemplates.java:29-90` |
| `graph_stats_nodes` / `graph_stats_relations` | — | NODES | `label,count` / `type,count` (UNION по `MATCH (n:L) count`) | `ConsoleTemplates.java:47-82` |
| `graph_last_update` | — | NODES | `lastUpdatedAt = max(SourceRecord.fetchedAt)` | `:88-97` |
| `console_neighborhood_nodes` / `console_neighborhood_edges` | `gid`,`relTypes` / `gids`,`relTypes` | NODES | узлы с `distance` и связи `type, source, target` | `:105-152` |
| `console_latest_sync_runs` | `adapters` | NODES | последний `SyncRun` на адаптер: `runId, startedAt, endedAt, status, fetched, applied, failed` | `:159-179` |

Всего объявлено 14 `QueryTemplate`: 4 в `AssetTemplates`, 3 в `DependencyTemplates`, 1 в `RuntimeTemplates`, 6 в `ConsoleTemplates` (`ConsoleTemplates.ALL`, `:182-189`). Реестр собирают приложения (X2: `GraphReaderConfiguration`, X3: `GraphReaderConfiguration`); `ConsoleTemplates.ALL` не включает шаблоны `AssetTemplates` — консоль регистрирует их отдельно (Javadoc `:13`, `:181`).

### X1.4 Структуры данных

Нет JSON-схемы: результат — `QueryResult(templateId, rows, truncated, rowCount, elapsed)` (`GQC/QueryResult.java:19-27`), `rows` — `List<Map<String,Object>>` из простых Java-структур; `rows` копируется (`List.copyOf`). `QueryTemplate`, `QueryLimits`, `ResultBudget`, `QueryResult`, `BudgetedCollector.Collected` — records; подробности — `data-dictionary.md` §X.1.

### X1.5 Константы и конфигурация

`TIMEOUT_CODE = "TransactionTimedOut"` (`GQC/GraphQueryExecutor.java:38`); `LIMIT_PARAMETER = "limit"`, `MAX_DEPTH_PLACEHOLDER = "{maxDepth}"` (`QueryTemplate.java:25,28`); `EXACT_SCORE = 1000.0` (`AssetTemplates.java:20`). Свойств Spring в модуле нет: `QueryLimits` создаёт вызывающее приложение (значения по умолчанию — X2.4, X3.4).

### X1.6 Сомнения и расхождения

- 🟡 **`GraphQueryExecutor` без unit-теста в модуле.** Вся логика исполнителя (clamp → render → EXPLAIN-кэш → обход строк → перевод таймаута) проверяется только IT в `platform/integration-tests` (`GraphQueryCoreIT.java:78-229`); `mvn -pl access/graph-query-core verify` их не запускает. Проверить в R.2e.
- 🟡 **Расхождение ADR 0005 и кода по `EXPLAIN`.** ADR 0005 в таблице вариантов **отклоняет** «Разбор Cypher на лету (`EXPLAIN`)» (`docs/adr/0005-readonly-mcp-by-application.md:22`), а код и `docs/03-interfaces.md:42,655` используют `EXPLAIN` как дополнительный слой. Противоречия по сути нет (в ADR отклонён `EXPLAIN` как *замена* статических шаблонов, а не как проверка поверх них), но ADR слой не описывает. Документ вне этапа — не правится.
- 🟡 **Обход фильтрует типы связей после раскрытия.** `[*1..{maxDepth}]` в `trace_*` и `console_neighborhood_nodes` не ограничен типами связей в самом паттерне; `type(r) IN $relationTypes` стоит в `WHERE all(...)` (`DependencyTemplates.java:31`, `ConsoleTemplates.java:113-115`). Перечисление путей по всем типам связей ограничивают только `LIMIT` и таймаут транзакции; стоимость на плотном графе не измерена в этом модуле (замеры — `docs/04-poc-measurements.md`).
- 🟡 **Порядок путей не определён.** `trace_*` без `ORDER BY` (сортировка в Java, Javadoc `DependencyTemplates.java:35-38,79-80`): при усечении по `LIMIT` состав путей может меняться между вызовами. Усечение сообщается флагом `truncated` на уровне `QueryResult`.
- 🟡 **Усечение по `LIMIT` ≠ усечение по `maxDepth`.** `truncated` выставляется только бюджетом строк/байт; обрезка глубиной (`maxDepth` меньше реальной цепочки) в результате не отражается. Вывод из `GQC/BudgetedCollector.java:41-58` и `QueryTemplate.java:133-138`.
- 🟡 **Состав `LABELS` в `trace_*` включает `Environment`, `Team`.** Начать обход можно с любой из 11 меток (`DependencyTemplates.java:22-27,85`); допустимость старта проверяют tools (X2).
- 🟡 **Дублирование `cypherList`.** Одноимённый приватный метод в `AssetTemplates.java:215`, `DependencyTemplates.java:128`, `ConsoleTemplates.java:200` (три копии).
- 🟡 **Вердикт `EXPLAIN` по умолчанию передаёт `null` во все параметры** (`GQC/GraphQueryExecutor.java:112-114`). Для шаблонов с `UNWIND $gids`/`IN $gids` планировщик строит план на `null`; на корректность read-only это не влияет.
- 🟢 Ловушка 2 (`*1..$depth` / `*` без верхней границы) закрыта на двух уровнях: валидация шаблона (`QueryTemplate.java:87-104`) и проверка `render` (`:133-136`); тесты — `QueryTemplateRegistryTest` (13 тестов).
- 🟢 Ловушка 3 (MCP только читает): в модуле единственное обращение к драйверу — `executeRead` (`GQC/GraphQueryExecutor.java:82,118`); `executeWrite`/`session.run` отсутствуют (grep по `access/graph-query-core/src/main`).


---

## X2. `access/mcp-server`

Сокращение: `MCP` = `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver`.

**Назначение.** Spring Boot-приложение MCP-сервера: Streamable HTTP на `POST /mcp` в режиме `STATELESS`, 6 tools (из них 5 предметных и служебный `ping`), OAuth2 resource server (JWT: issuer + audience), scope на каждый `tools/call`, аудит и метрики каждого вызова, чтение графа только через `graph-query-core` (`access/mcp-server/src/main/resources/application.yml:11-13,19-20`; `MCP/McpServerApplication.java:6-8`) 🟢. Зависимости: `graph-query-core`, `neo4j-java-driver`, `spring-ai-starter-mcp-server-webmvc`, actuator, Micrometer OTLP, AspectJ, `oauth2-resource-server` (`access/mcp-server/pom.xml`); `ingestion/*` и `adapters/*` не подключены, writer-свойств Neo4j нет — только `archrag.neo4j.reader.*` (`access/mcp-server/src/main/resources/application.yml:36-41`) 🟢. Состав: 24 Java-файла main (без `package-info`), ≈2150 строк; тестов 100 `@Test` в 16 классах: tools — 39 (`TraceDependenciesToolTest` 15, `ExplainProvenanceToolTest` 8, `FindRuntimeFootprintToolTest` 6, `GetAssetToolTest` 6, `SearchAssetsToolTest` 4), через HTTP `@SpringBootTest` — 44 (`McpSecurityTest` 13, `McpAuditTest` 9, `TraceDependenciesMcpTest` 8, `McpServerInteropTest` 4, `GetAssetMcpTest` 4, `SearchAssetsMcpTest` 3, `FindRuntimeFootprintMcpTest` 3), прочее — 17 (`ToolArgumentsTest` 7, `ToolCallAuditorTest` 5, `GraphReaderConfigurationTest` 3, `ActuatorProbesTest` 2 и т. д.). Neo4j в тестах модуля не поднимается: драйвер ленивый, `GraphQueries` подменяется (`access/mcp-server/src/test/resources/config/application.yml:1-7`) 🟡 — сквозное чтение графа проверяют IT платформы (R.2e).

### X2.1 Поток управления

**Вызов tool** (последовательность по слоям) 🟢:
1. Spring Security: `BearerTokenAuthenticationFilter` проверяет JWT (issuer из `ARCHRAG_OIDC_ISSUER_URI`, audience = `archrag.mcp.security.resource-uri`) (`access/mcp-server/src/main/resources/application.yml:19-20`; `MCP/security/McpSecurityConfiguration.java:48-50`). Без токена — 401. Открыты только `/actuator/health/**` и `/.well-known/oauth-protected-resource/**` (`:43-47`).
2. `ToolScopeFilter` (после `AuthorizationFilter`, `:61`), только для `POST /mcp` (`MCP/security/ToolScopeFilter.java:51-54`): отвергает неизвестную кодировку (400), тело больше `max-request-bytes` (по умолчанию 1 МБ, `McpSecurityProperties.java:21`) → 413, не-JSON и всё, что не JSON-**объект** (в том числе JSON-RPC batch), → 400 (`:60-81`). Для `method == "tools/call"` берёт `params.name`, ищет scope в `tool-scopes`; нет записи → warn в лог, аудит `DENIED_UNKNOWN_TOOL`, 403; у токена нет `SCOPE_<scope>` → аудит `DENIED_SCOPE`, 403 с `WWW-Authenticate: Bearer error="insufficient_scope", scope=…, resource_metadata=…` (`:82-96,124-132`). Остальные методы MCP (`initialize`, `tools/list` и др.) требуют только валидный токен — подтверждено `McpSecurityTest.tokenWithoutScopeCanInitializeAndListTools` (`McpSecurityTest.java:96`). Тело передаётся дальше буферизованным (`BufferedRequest`, `:144-189`).
3. Spring AI MCP (stateless): диспетчеризация в метод с `@McpTool`.
4. `ToolCallAuditAspect` вокруг каждого метода `@McpTool`: открывает `ToolCallContext` (ThreadLocal) и наблюдение Micrometer, вызывает tool, в `finally` закрывает контекст и пишет аудит; исключение tool не глушится (`MCP/audit/ToolCallAuditAspect.java:29-60`). Решение: нет исключения → `ALLOWED`; `QueryTimeoutException` → `TIMEOUT`; иное → `ERROR` (`:43-46`).
5. Tool валидирует аргументы (все ошибки — `IllegalArgumentException` без эха ввода), формирует `ResultBudget` и вызывает `GraphQueries.execute(templateId, params, budget)` (`MCP/graph/GraphQueries.java:27-31`), который вызывает `GraphQueryExecutor` и записывает шаблон/строки/`truncated` в `ToolCallContext` (`ToolCallContext.record`, `:35-42`).
6. Tool собирает типизированный ответ (record) — Spring AI сериализует его в `content[0].text` JSON.

**Tools** (все с `readOnlyHint=true, destructiveHint=false, idempotentHint=true, openWorldHint=false`) 🟢:

| Tool | Scope | Параметры (обяз.*) | Шаблоны графа | Ошибки (`IllegalArgumentException`) | Файл |
|---|---|---|---|---|---|
| `ping` | `architecture.read` | — | — | — | `MCP/tools/PingTool.java:11-22` |
| `search_assets` | `architecture.read` | `query`*, `types[]`, `environment`, `limit` (1..50, по умолч. 10) | `search_assets` | пустой/длиннее 200 `query`, `limit` вне диапазона, метка вне `SEARCHABLE_TYPES` | `MCP/tools/SearchAssetsTool.java:39-107` |
| `get_asset` | `architecture.read` | `gid`*, `includeRelations` (по умолч. true) | `get_asset` (+ `asset_relations`) | `gid` не UUID, `asset not found` | `MCP/tools/GetAssetTool.java:42-92` |
| `explain_provenance` | `architecture.read` | `gid`*, `property` (`^[A-Za-z][A-Za-z0-9_]{0,63}$`) | `explain_provenance` | `gid` не UUID, `property is invalid`, не найден | `MCP/tools/ExplainProvenanceTool.java:41-88` |
| `trace_dependencies` | `architecture.read` | `gid`*, `direction`, `maxDepth` (1..лимит), `relationTypes[]`, `mode` (`trace`\|`impact`), `environment` (только impact, ≤128) | `trace_downstream` / `trace_upstream` / `trace_impact` (+ `get_asset` при пустом результате) | неизвестный режим/направление, `maxDepth` вне диапазона, тип связи вне allowlist режима, не найден | `MCP/tools/TraceDependenciesTool.java:82-158` |
| `find_runtime_footprint` | `architecture.read` | `systemGid`*, `environment` | `runtime_footprint` | `systemGid` не UUID, `asset not found` (также если не `ITSystem`/не текущая) | `MCP/tools/FindRuntimeFootprintTool.java:46-77` |

Все 6 строк `tool-scopes` ведут на единственный scope `architecture.read` (`access/mcp-server/src/main/resources/application.yml:28-34`) 🟢: разделения прав между tools нет.

**Сборка ответов** 🟢:
- `search_assets`: строка → `AssetHit`; `truncated` = результат обрезан бюджетом `min(limit, maxNodes)` (`SearchAssetsTool.java:84-92`).
- `get_asset`: `properties` — `properties(n)` без служебных ключей; `java.time` → ISO-8601 UTC (`GetAssetTool.java:104-125`); `truncated` относится **только** к связям (`:75-80`).
- `explain_provenance`: мастера по `AuthorityMatrix.defaults()` из `canonical-model` для метки узла (и свойства), неизвестная метка → пустое множество; `authoritative = source ∈ мастера`; с `property` конфликты фильтруются по имени; `conflictState = OPEN`, если у какой-либо записи остался конфликт (`ExplainProvenanceTool.java:76-87,111-120,130-150`).
- `trace_dependencies`: строки-пути сортируются в Java по `(число узлов, gid узлов)` (`PATH_ORDER`, `:37-50,200-201`); узлы и связи дедуплицируются (связь по `(type, from, to)`); `stale` узла — `lastSeenAt` пуст или старше `now − stale-after`; `stale` связи — запись-источник не найдена, неактивна или старше порога; для `impact` `affected` = последние узлы путей (`:193-239`). Пустой результат при существующем узле допустим, иначе `asset not found` через `get_asset` (`:154-156,161-163`).
- `find_runtime_footprint`: группировка строк в `service → deployment → compute (+host)`; `warnings` — по строке на устаревший узел (`FindRuntimeFootprintTool.java:79-150`); `truncated` из бюджета (`:139-149`).

### X2.2 Алгоритмы и встроенные правила

1. **Fail-closed для tools.** Любой `tools/call` без записи в `tool-scopes` отвергается (`ToolScopeFilter.java:84-90`); ловушка 13 из `CLAUDE.md` закрыта конфигурацией, а не аннотацией 🟢. Тест `unmappedToolIsForbiddenEvenWithScope` (`McpSecurityTest.java:178`).
2. **Бюджет** — запрошенный глубины/лимита режется до `QueryLimits` в executor'е; tool `trace_dependencies` дополнительно проверяет `maxDepth ≤ limits.maxDepth` и выдаёт ошибку, а не молча режет (`TraceDependenciesTool.java:134-137`) 🟢. `search_assets` режет `limit` через `min(limit, maxNodes)` после проверки 1..50 (`SearchAssetsTool.java:70-73,87`).
3. **Нет эха ввода в ошибках.** `gid must be a UUID`, `property is invalid`, `Unknown relation type; allowed: …` (список — из констант) — значения ввода в текст не попадают; исходное сообщение `UUID.fromString` специально не пробрасывается (`GetAssetTool.java:94-101`, `ExplainProvenanceTool.java:90-108`, `TraceDependenciesTool.java:165-185`) 🟢.
4. **Аудит без утечки.** Запись логгера `archrag.audit.mcp` (`principal`, `tool`, `arguments`, `templates`, `durationMs`, `rows`, `truncated`, `decision`, `errorClass`) — одна на вызов, включая отказы фильтра (`MCP/audit/ToolCallAuditor.java:99-120`); `principal` = `sub` + `azp`/`client_id`, токен не читается (`:132-144`); аргументы нормализуются `ToolArguments`: ключи по алфавиту, строки до 256 символов, коллекции до 20 элементов, глубина до 5, значения ключей по маске `token|secret|password|authorization|credential` → `***` (`ToolArguments.java:20-26,35-79`); имя tool из запроса очищается от управляющих символов и режется до 64 (`:122-129`); метрики: наблюдение `archrag.mcp.tool.call` (теги `tool`, `decision`) и summary `archrag.mcp.tool.rows`; тег `tool` для неизвестного tool — `unknown` (низкая кардинальность) (`:29-33,91-97`) 🟢. Сбой самого аудита логируется классом исключения и не подменяет результат tool (`:79-82`).
5. **Свежесть.** `stale-after` по умолчанию `P7D`, должен быть положительным (`MCP/graph/StalenessProperties.java:13-19`); сравнение строго: ровно на пороге — свежо (`TraceDependenciesTool.java:187-190`).
6. **Потолки** (по умолчанию, `access/mcp-server/src/main/resources/application.yml:45-50`): `max-depth 6`, `max-nodes 500`, `max-paths 50`, `timeout 5s`, `max-response-bytes 512KB`; конфигурация — `GraphReaderProperties.Limits` (`MCP/graph/GraphReaderProperties.java:32-34`) → `QueryLimits` (`MCP/graph/GraphReaderConfiguration.java:41-49`). Значения без умолчаний в коде (`@ConfigurationProperties` record без `@DefaultValue`): при пропуске свойств `new QueryLimits` упадёт на инвариантах → приложение не стартует 🟡.
7. **Драйвер**: один бин `Driver` (`destroyMethod = "close"`), `AuthTokens.basic`, создаётся лениво — соединение при первом запросе; `spring.neo4j.*` autoconfig не используется (`MCP/graph/GraphReaderConfiguration.java:35-39`, Javadoc `:20-26`). `GraphReaderProperties.toString` маскирует пароль (`MCP/graph/GraphReaderProperties.java:17-21`) 🟢.
8. **Метаданные OAuth.** Protected Resource Metadata (RFC 9728) публикуется на `/.well-known/oauth-protected-resource` без токена: `resource`, `authorization_servers`, `scopes_supported` (уникальные scope из `tool-scopes`), `resource_name = arch-rag` (`McpSecurityConfiguration.java:33,51-60`) 🟢; в `WWW-Authenticate` 403 есть `resource_metadata` (`ToolScopeFilter.java:129,134-139`).

### X2.3 Структуры данных

Записи ответов — в `data-dictionary.md` §X.2 (`SearchAssetsResult`, `GetAssetResult`, `ExplainProvenanceResult`, `TraceDependenciesResult`, `FindRuntimeFootprintResult`), аудит-запись — §X.2.2, свойства — §X.2.3.

### X2.4 Константы и конфигурация

`DEFAULT_LIMIT=10`, `MAX_LIMIT=50`, `MAX_QUERY_LENGTH=200` (`SearchAssetsTool.java:21-23`); `MAX_ENVIRONMENT_LENGTH=128` (`TraceDependenciesTool.java:35`); `PROPERTY` regex (`ExplainProvenanceTool.java:24`); `ToolArguments.MAX_STRING/ITEMS/DEPTH=256/20/5` (`:20-22`); `ToolCallAuditor.LOGGER="archrag.audit.mcp"`, `OBSERVATION="archrag.mcp.tool.call"`, `MAX_TOOL_NAME=64` (`:29-33`); `MCP_PATH="/mcp"` (`ToolScopeFilter.java:40`). Обязательные env без значения по умолчанию: `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_NEO4J_READER_URI/USERNAME/PASSWORD` (`access/mcp-server/src/main/resources/application.yml:19,25,39-41`). Actuator: наружу только `health` с probes; OTLP-метрики выключены по умолчанию (`access/mcp-server/src/main/resources/application.yml:55-80`).

### X2.5 Сомнения и расхождения

- 🟡 **`find_runtime_footprint` не нормализует `systemGid`.** `UUID.fromString` лишь проверяет формат, а в Cypher уходит **исходная строка** без `strip`/`toString` (`FindRuntimeFootprintTool.java:62-69`), тогда как `get_asset`, `explain_provenance`, `trace_dependencies` приводят `gid` к каноническому нижнему регистру (`GetAssetTool.java:94-101`). `UUID.fromString` допускает и заглавные буквы, и укороченные формы (`1-1-1-1-1`): такой ввод пройдёт валидацию, не совпадёт с `gid` в графе и вернёт `asset not found`. Пробелы по краям `systemGid` приведут к `must be a UUID` (в других tools `strip` их убирает).
- 🟡 **Один scope `architecture.read` на все tools.** Разграничения по tools нет (`access/mcp-server/src/main/resources/application.yml:28-34`). Схема `tool-scopes` позволяет завести разные scope; сейчас это единственный.
- 🟡 **Согласованность `@McpTool` ↔ `tool-scopes` проверяется только в рантайме.** На старте нет проверки, что для каждого зарегистрированного tool есть запись; забытый tool будет отвергаться 403 как «неизвестный» (`ToolScopeFilter.java:84-90`). В тестах модуля есть только негативный `unmappedToolIsForbiddenEvenWithScope` и `McpSecurityTest.toolsListMarksAllToolsReadOnly` (`:111`), не сверка списков.
- 🟡 **Недоверенный текст в ответах.** `get_asset` отдаёт `properties(n)` целиком (`access/graph-query-core/.../templates/AssetTemplates.java:120`, `GetAssetTool.java:104-114`) — включая любые текстовые поля источников (описания и т. п.) без пометки «недоверенные данные»; на стороне сервера они не интерпретируются, но MCP-клиент (LLM) получит их как обычный JSON. Ловушка 11 про логи соблюдена (аргументы усекаются, значения свойств не логируются), про выдачу агенту — решения в коде нет.
- 🟡 **`tools/list` и `initialize` доступны токену без scope.** Это зафиксировано тестом (`McpSecurityTest.java:96`) и описывает метаданные tools (имена, описания, схемы) любому владельцу валидного токена с нужным audience.
- 🟡 **Ограничение аудита: ThreadLocal.** Tool, исполняющий запросы в другом потоке, не попадёт в `templates`/`rows` (Javadoc `ToolCallContext.java:7-13`). Сейчас все tools синхронны.
- 🟡 **Описание `search_assets` и шаблон.** Описание tool говорит «точное совпадение по gid или sourceId» (`SearchAssetsTool.java:47-50`), шаблон дополнительно ищет `ITSystem.eamCode` (`access/graph-query-core/.../templates/AssetTemplates.java:48-50`); fulltext-индекс `asset_text` построен только по `ITSystem|Service` (`ingestion/graph-projector/.../Neo4jSchema.java:57`) — описание это отражает.
- 🟡 **`get_asset`: `truncated` только по связям** (`GetAssetTool.java:75-91`); обрезка карточки (`sources`) бюджетом ответа в флаге не видна — шаблон возвращает одну строку с вложенным списком, при превышении `max-response-bytes` строка будет отброшена целиком, и вызов закончится `asset not found` 🟡 (поведение выведено из `BudgetedCollector.java:51-55` и `GetAssetTool.java:69-71`, тестом не подтверждено).
- 🟡 **Дубликат `AuthorityMatrix.defaults()`.** `ExplainProvenanceTool` создаёт собственный экземпляр (`:28`), как и два места в `ingestion-service` (Q5.5). Таблица одна (`canonical-model`), а экземпляров три — при смене правил менять придётся только `defaults()`, но кастомизации по конфигурации нет.
- 🟢 **ADR 0017 / `STATELESS`.** Код использует `protocol=STATELESS`, что отражено в ADR 0017 (уточнение 2026-10-07, UNLOCKER-224) и `docs/03-interfaces.md` §8 п.1; инструкция агента (`STREAMABLE`) устарела относительно ADR — расхождение закрыто документами, не кодом.
- 🟢 Ловушка 3 (MCP только читает): `GraphQueries` — единственная точка (Javadoc `GraphQueries.java:10-16`), tool'ы обращаются к `queries.execute`, не к `GraphQueryExecutor` (проверено чтением всех 5 tools); writer-учётных данных в конфигурации нет (`access/mcp-server/src/main/resources/application.yml:36-41`).
- 🟢 Ловушка 12: пароль Neo4j не печатается (`GraphReaderProperties.toString`), токен не читается и не попадает в аудит (`McpAuditTest.deniedCallsDoNotContainToken`, `McpAuditTest.java:134`).

---

## X3. `access/admin-console`

Сокращение: `AC` = `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole`.

**Назначение.** Отдельное Spring Boot-приложение «админ-консоль»: раздаёт собранный SPA (`admin-ui`) как статику с fallback на `index.html` и даёт **только читающий** REST под `/api/**`: сводка и обзор графа (через `graph-query-core`), журнал событий/DLQ/checkpoint'ы/идентичность/аудит админских операций (прямые `SELECT` в PostgreSQL ролью `archrag_console_ro`) (`AC/AdminConsoleApplication.java:6`, `docs/adr/0026-admin-console-readonly-loopback.md`) 🟢. Все 11 маршрутов контроллеров — `@GetMapping` (`GraphController.java:56,68,105,127`, `SyncController.java:56,78,97,113`, `IdentityController.java:30,41`, `AuditController.java:25`). **Вызовов в ingestion нет**: в `src/main` нет `RestClient`/`WebClient`/`HttpClient` и обращений к `/admin/*` (grep), replay/rebuild/reconcile/crosswalk остаются в admin REST ingestion (ADR 0013, 0026) 🟢. Зависит от `admin-ui` (jar со статикой в `META-INF/resources`), `graph-query-core`, `neo4j-java-driver`, Spring MVC/JDBC/Security (`access/admin-console/pom.xml:19-58`); `ingestion/*` не подключён: коды источников и статусы продублированы константами (`AC/pg/SyncSources.java:9-12`). Артефакты: обычный jar (его берёт `platform/integration-tests`) и `-exec` для запуска (`pom.xml:75-77`). Состав: 25 Java-файлов main (без `package-info`), ≈1775 строк; тестов 31 `@Test` в 3 классах (`ConsoleApiTest` 20, `SyncApiTest` 8, `ConsoleStartupTest` 3), все `@SpringBootTest(RANDOM_PORT)` с подменёнными `GraphQueryExecutor` и `ConsoleReadRepository` (`ConsoleApiTest.java:38-45`, `SyncApiTest.java:34-50`) — **SQL репозитория в модуле не выполняется**, его проверяет `platform/integration-tests/.../SyncConsoleIT.java` и `AdminConsoleIT.java` (R.2e) 🟡.

### X3.1 Поток управления и REST

**Запрос `/api/**`** 🟢:
1. JWT (issuer, обязательный audience = `archrag.console.resource-uri`) → `hasAuthority(SCOPE_architecture.admin)`; нет токена — 401, нет scope — 403 (`AC/security/ConsoleSecurityConfiguration.java:38-53`; `ConsoleApiTest.java:94,100,108`).
2. Spring MVC → `ReadAuditInterceptor.preHandle` создаёт `ReadAuditContext` в атрибутах запроса (`AC/audit/ReadAuditInterceptor.java:31-35`, регистрация на `/api/**` в `AC/config/SpaWebConfiguration.java:42-45`).
3. Контроллер валидирует параметры (`InvalidRequestException` → 400 `{error: invalid_request, message}`), читает граф через `GraphReads` (шаблон + бюджет, результат пишется в контекст аудита) либо PG через `ConsoleReadRepository` (`recordSql("sql:<table>", rows)`).
4. `afterCompletion` пишет одну запись `archrag.audit.console`: `sub`, `endpoint` (метод + шаблон маршрута), `templates`, `rows`, `truncated`, `status`, `durationMs` (`:38-53`).

**Маршруты** 🟢 (все требуют scope `architecture.admin`):

| Маршрут | Параметры (валидация) | Источник | Ответ | Файл |
|---|---|---|---|---|
| `GET /api/graph/stats` | — | шаблоны `graph_stats_nodes`, `graph_stats_relations`, `graph_last_update` | `Stats{nodes[Count], relations[Count], lastUpdatedAt}` | `GraphController.java:56-65` |
| `GET /api/graph/search` | `q`* (≤ 200), `types[]` (allowlist `SEARCHABLE_TYPES`), `limit` 1..50 (по умолч. 10) | `search_assets` (`environment=null`) | `SearchResponse{items[AssetHit], truncated}` | `:68-102` |
| `GET /api/graph/nodes/{gid}` | `gid` UUID | `get_asset` + `explain_provenance` | `NodeCard` (404 `asset not found`); `sources` = записи provenance | `:105-124` |
| `GET /api/graph/nodes/{gid}/neighborhood` | `depth` 1..2 (по умолч. 1), `relTypes[]` (allowlist `NEIGHBORHOOD_RELATION_TYPES`) | `console_neighborhood_nodes`, `console_neighborhood_edges` | `Neighborhood{center, nodes, edges, truncated}` | `:127-167` |
| `GET /api/sync/sources` | — | PG: `consumer_checkpoint`, счётчики и `max(updated_at)` по `inbox_event`; граф: `console_latest_sync_runs` | `[SourceStatus]` для всех 4 источников (`eam, scm, cmdb, deploymap`), включая нули; `lagSeconds = now − последний PROJECTED` | `SyncController.java:56-75` |
| `GET /api/sync/events` | `source`, `status` (allowlist), `from`<`to` (Instant), `page`, `size` ≤ 200 | PG `inbox_event` | `Page<Event>` | `:78-91` |
| `GET /api/sync/events/{source}/{eventId}` | `source` в allowlist | PG `inbox_event`, `error_reason`, `dlq_entry` | `EventDetail{event, errorReason, dlqEntries, historyNote}`; 404 | `:97-110` |
| `GET /api/sync/dlq` | `source`, `replayed` (bool), `page`, `size` | PG `dlq_entry` | `Page<DlqEntry>` | `:113-121` |
| `GET /api/identity/conflicts` | `status` (по умолч. `OPEN`), `gid` UUID, `page`, `size` | PG `source_conflict` | `Page<Conflict>` (с усечёнными до 500 символов значениями) | `IdentityController.java:30-38` |
| `GET /api/identity/candidates` | `status` (по умолч. `OPEN`), `labelFamily`, `page`, `size` | PG `identity_candidate` | `Page<Candidate>` | `:41-49` |
| `GET /api/audit` | `operation`, `status` (токены `[A-Za-z_]{1,64}`), `from`<`to`, `page`, `size` | PG `admin_audit` | `Page<Audit>` (`request`/`result` длиннее 4000 символов не отдаются, флаги `…Truncated`) | `AuditController.java:25-39` |

Вне `/api`: `GET /console-config.json` (публично: `issuer`, `clientId`, `scope` — без секретов, `AC/config/ConsoleConfigController.java:18,27-30`), `GET /actuator/health/**` (публично), остальной `/actuator/**` и любой неGET вне `/api` — `denyAll` (`ConsoleSecurityConfiguration.java:42-52`), статика и маршруты SPA — `GET /**` публично (`:48-50`).

**Ошибки** (`AC/api/ApiExceptionHandler.java:23-41`) 🟢: `InvalidRequestException`/ошибка типа параметра → 400 `invalid_request`; `NotFoundException` → 404 `not_found`; `QueryTimeoutException` → 504 `query_timeout` (`graph query timed out`). Значения ввода в сообщения не попадают (`gid must be a UUID`, `Unknown source; allowed: …`). `Neo4jException`, `IllegalStateException` («not read-only», «Node in result is forbidden») и `DataAccessException` отдельных обработчиков не имеют — отвечают стандартным 500 Spring Boot 🟡.

**SPA-раздача** (`AC/config/SpaWebConfiguration.java:33-71`) 🟢: ресурсы из `classpath:/META-INF/resources/`, `Cache-Control: no-cache`; `SpaFallbackResolver` отдаёт `index.html` для путей **без точки в последнем сегменте**, кроме `api/**` и `actuator/**`; отсутствующий файл с расширением → 404, а не HTML (инвариант Javadoc `:17-18`; тест `staticFilesAndSpaRoutesArePublicAndFallBackToIndex`, `ConsoleApiTest.java:172`). Дефолтные маппинги Boot выключены (`access/admin-console/src/main/resources/application.yml:9`).

**Заголовки безопасности** (`ConsoleSecurityConfiguration.java:34-37,61-66`) 🟢: `X-Content-Type-Options: nosniff` и CSP `default-src 'self'; connect-src 'self' <origin issuer>; frame-ancestors 'none'; base-uri 'self'; object-src 'none'`; в `connect-src` — **origin** issuer, а не полный URI (источник с путём совпадает только с точным путём и блокировал бы token endpoint) (Javadoc `:57-60`). Тест `securityHeadersArePresentOnStaticAndApi` (`ConsoleApiTest.java:146`) и `bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles` (`:183`).

### X3.2 Алгоритмы и встроенные правила

1. **Только читающие источники.** Граф — reader-учётка Neo4j, `GraphQueryExecutor` (X1), реестр = `ConsoleTemplates.ALL` + `search_assets`, `get_asset`, `explain_provenance` (`AC/graph/GraphReaderConfiguration.java:44-52`). PG — пул Hikari: `readOnly=true`, `connectionInitSql = SET default_transaction_read_only = on`, максимум 4 соединения, `initializationFailTimeout = -1` (ленивое соединение, консоль живёт без PG) (`AC/pg/ConsolePgConfiguration.java:22-36`); `management.health.db.enabled=false` (`access/admin-console/src/main/resources/application.yml:49`). Ловушка 3 соблюдена: ни `executeWrite`, ни DML в `src/main` (grep по `INSERT|UPDATE|DELETE|MERGE`) 🟢.
2. **SQL без конкатенации ввода.** Условия `WHERE` собираются из константных фрагментов `Where.eq(fragment, name, value)`, значения — именованные параметры JdbcClient; `null`-фильтры пропускаются; таблица, колонки и `ORDER BY` — константы вызывающего кода (`AC/pg/ConsoleReadRepository.java:270-290,343-365`) 🟢. `ORDER BY` везде детерминирован составным ключом (`…, id`/`event_id`).
3. **Пагинация**: `PageRequest.of(page, size)` — `page ≥ 0`, `1 ≤ size ≤ 200`, по умолчанию 0 и 50 (`AC/pg/PageRequest.java:10-24`); ответ `Page{items, page, size, total}`, `total` — отдельный `count(*)` под тем же фильтром (`ConsoleReadRepository.java:273-277`) 🟢. `OFFSET` не ограничен сверху (глубокие страницы дорогие) 🟡; `count` и страница читаются двумя запросами без общей транзакции — `total` может разойтись с `items` при параллельной записи ingestion 🟡.
4. **Allowlist'ы и токены.** Источник ∈ `{eam, scm, cmdb, deploymap}` → в БД `urn:corp:<код>`; статус ∈ 10 статусов `ProcessingStatus`; `status`/`labelFamily`/`operation` — `[A-Za-z_]{1,64}` (`AC/pg/SyncSources.java:19-62`, `IdentityController.java:21,51-63`). Значения ввода в ошибку не попадают.
5. **Payload не читается и не отдаётся**: в выборках нет колонок payload, отдаются `payloadRef` (= `payload_key`) и `contentHash` (= `payload_hash`) (`AC/pg/Rows.java:7-8,14-32`) 🟢; тест `responseTypesDeclareNoPayloadComponent` (`SyncApiTest.java:136`).
6. **Тексты источников недоверенные (ловушка 11)**: `reason`, `error_reason`, `dissent_value`, `master_value`, `error` режутся до 500 символов (`ConsoleReadRepository.java:34,338-340`); `request`/`result` аудита больше 4000 символов не отдаются совсем (`CASE WHEN length(…::text) > 4000 THEN NULL`, `:35,242-245`) 🟢; аргументы запроса в журнал аудита не пишутся (`auditDoesNotLogSearchQuery`, `ConsoleApiTest.java:391`).
7. **Отставание**: `lagSeconds = max(0, now − max(updated_at) среди PROJECTED)` на UTC-часах `Clock`, `null` если PROJECTED не было (`SyncController.java:62-70`); тест `lagIsComputedFromInjectedClock` (`SyncApiTest.java:176`) 🟢. Источники в сводке — всегда 4 (с нулями), статусы — все 10 (`:63-73`).
8. **Окрестность**: глубина ≤ 2 (константа контроллера, независимо от потолка конфигурации 6), узлы затем рёбра между ними одним запросом по списку `gids`, `truncated = nodes.truncated || edges.truncated` (`GraphController.java:44,127-167`); тест IT `neighborhoodAcceptsDeployedFromRelType`, `neighborhoodOverNodeBudgetIsTruncated` (`AdminConsoleIT.java:246,272`) 🟢.
9. **Аудит консоли и отказы безопасности**: аудит консоли пишется **только для запросов, дошедших до контроллера** (`HandlerInterceptor`), 401/403 из Spring Security в него не попадают (в отличие от MCP, где отказы пишутся явно) 🟡.

### X3.3 Структуры данных

Ответы API — `data-dictionary.md` §X.3 (`GraphResponses`, `Rows`, `Page`, `ApiError`, `ConsoleConfig`); конфигурация — §X.3.3.

### X3.4 Константы и конфигурация

`DEFAULT_LIMIT=10`, `MAX_LIMIT=50`, `MAX_QUERY_LENGTH=200`, `MAX_DEPTH=2` (`GraphController.java:40-44`); `PageRequest.DEFAULT_SIZE=50`, `MAX_SIZE=200`; `ConsoleReadRepository.MAX_TEXT=500`, `MAX_RESULT_CHARS=4000`; `ConsoleProperties.ADMIN_SCOPE="architecture.admin"` (`AC/config/ConsoleProperties.java:20`); `HISTORY_NOTE` (`SyncController.java:39-40`); `LOGGER="archrag.audit.console"` (`ReadAuditInterceptor.java:25`). Обязательные env: `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_CONSOLE_RESOURCE_URI` (пустое значение → отказ запуска, `ConsoleProperties.java:22-27`, `ConsoleStartupTest.java:15,33`), `ARCHRAG_NEO4J_READER_URI/USERNAME/PASSWORD`, `ARCHRAG_CONSOLE_PG_URL/PASSWORD`; `ARCHRAG_CONSOLE_PG_USERNAME` по умолчанию `archrag_console_ro` (`access/admin-console/src/main/resources/application.yml:14-34`). Тест `configurationHasReadOnlyCredentialsOnly` проверяет, что writer-свойств нет (`ConsoleStartupTest.java:40`).

### X3.5 Сомнения и расхождения

- 🟡 **Консоль отдаёт значения свойств в конфликтах** (`dissentValue`, `masterValue`, до 500 символов), тогда как MCP `explain_provenance` значений намеренно не отдаёт (`ExplainProvenanceResult.java:6-7`). Для оператора со scope `architecture.admin` это ожидаемо; ADR 0026 об этом не говорит. Стоит зафиксировать как осознанное различие.
- 🟡 **Любой пользователь realm может получить токен консоли** (ADR 0026: «ограничение PoC», принято владельцем 2026-10-08, gap list п. 2.7) — в коде консоли проверки `azp`/роли нет; `AuthorizedPartyValidator` есть только в ingestion (Q5). Расхождения с ADR нет.
- 🟡 **Копипаст с MCP.** `GraphReaderConfiguration` и `GraphReaderProperties` (`AC/graph/*`) почти дословно повторяют `MCP/graph/*`; в `GraphController` повторены `normalizeGid`, `hit`, `properties`, `jsonValue`, `SERVICE_KEYS`, `GraphResponses.AssetHit` ≡ `SearchAssetsResult.AssetHit` (`GraphController.java:46-47,190-250`, `GetAssetTool.java:25-26,94-125`). Общего модуля нет (Spring-конфигурация не может жить в `graph-query-core` без зависимости на Spring); при изменении правил нормализации придётся менять в двух местах.
- 🟡 **Пятая копия таблицы кодов источников** — `SyncSources.CODES` и `ADAPTERS = CODES.toUpperCase()` (`SyncSources.java:19`, `SyncController.java:37`). R.2c насчитал четыре копии (Q6.1), эта — вне их; `SyncRun.adapter` в графе должен быть в верхнем регистре (`DEPLOYMAP` ↔ `deploymap`), что держится на соглашении, а не на тесте.
- 🟡 **Статусы журнала продублированы** списком `STATUSES` вместо `ProcessingStatus` (`SyncSources.java:22-33`) — консоль сознательно не зависит от ingestion; при добавлении статуса фильтр консоли его не примет (400).
- 🟡 **Метрика/фильтр DLQ «открытые»** опирается на `dlq_entry.replayed_at` (`ConsoleReadRepository.java:157-159`), которое нигде не заполняется (Н1/Q5.5): фильтр `replayed=false` вернёт всё, `replayed=true` — ничего. Подтверждено: в консоли это проявляется так же.
- 🟡 **`GET /api/sync/events/{source}/{eventId}` не работает для `eventId` с `/`** (Javadoc `SyncController.java:94`) — события `poll:…`/`snap:…` с косой чертой в идентификаторе в консоли открыть нельзя; список `/api/sync/events` их показывает.
- 🟡 **Необработанные исключения → стандартный 500.** `Neo4jException` (кроме таймаута), `IllegalStateException` из executor'а и ошибки БД обработчиков не имеют (`ApiExceptionHandler.java:23-41`); тело ошибки определяет Spring Boot.
- 🟡 **`/api/**` с не-GET методом.** Правило безопасности для `/api/**` не фильтрует метод (`ConsoleSecurityConfiguration.java:44-45`): POST с admin-токеном проходит авторизацию и отклоняется уже MVC (в контроллерах нет не-GET). Тест `otherActuatorEndpointsAndWritesAreDenied` проверяет только «не 2xx» (`ConsoleApiTest.java:129-143`), не конкретный код.
- 🟡 **Токен консоли и audience.** Audience консоли (`urn:archrag:console`) — отдельный от MCP и admin REST (ADR 0026); токены не взаимозаменяемы по `aud` (`ConsoleApiTest.apiWithForeignAudienceIsUnauthorized`, `:108`).
- 🟢 Ловушка 12: пароли Neo4j/PG не печатаются (`GraphReaderProperties.toString`, `ConsolePgProperties.toString`), токен не читается (`ReadAuditInterceptor.subject`, `:61-68`).
- 🟢 Соответствие SQL и прав: V6 даёт `SELECT` ровно на `inbox_event, consumer_checkpoint, dlq_entry, identity_candidate, source_conflict, admin_audit` (Q.5.2), репозиторий читает ровно эти таблицы (`ConsoleReadRepository.java:55,70,85,120,129,141,275`).

---

## X4. `access/admin-ui`

Сокращение: `UI` = `access/admin-ui/src`.

**Назначение.** SPA админ-консоли: React 18 + TypeScript + Webpack, собирается Maven-модулем (`packaging` jar) через `frontend-maven-plugin`; бандл попадает в `target/classes/META-INF/resources` и отдаётся `admin-console` как статика (`access/admin-ui/pom.xml:26-80`, `access/admin-ui/webpack.config.js:6,13-14`, ADR 0027) 🟢. В Java-иерархии у модуля нет кода: он зависит только от `admin-console` как от потребителя (`admin-console/pom.xml:19-24`). Состав: 26 файлов TS/TSX без тестов, ≈1730 строк (+ `index.html`, `styles.css`); тестов Jest 53 в 6 файлах (`GraphPage.test.tsx` 17, `SyncPage.test.tsx` 16, `api.test.tsx` 7, `authService.test.ts` 7, `App.test.tsx` 4, `useHashRoute.test.ts` 2). Сторонние библиотеки не разбираются; прямые зависимости (`access/admin-ui/package.json:10-14`): `react`/`react-dom` 18.3.1, `oidc-client-ts` 3.1.0, `cytoscape` 3.30.4; версии закреплены точно, `package-lock.json` в Git (ADR 0027) 🟢.

### X4.1 Поток управления

**Сборка (Maven)** 🟢: `install-node-and-npm` (Node v22.12.0, npm 10.9.0) и `npm ci` в `generate-resources`; `npm test` в `test` (пропускается `-DskipTests`); `npm run build` (`webpack --mode production`) в `prepare-package` (`access/admin-ui/pom.xml:18-24,35-77`; `package.json:7-9`). Webpack: вход `src/index.tsx`, `ts-loader`, CSS выносится в файл `MiniCssExtractPlugin` (`style-loader` не используется — он вставляет `<style>`, а CSP консоли запрещает inline), имена с `contenthash`, `devtool: false` в production (исходники в jar не публикуются), HTML не минифицируется самим webpack, чтобы тесты Java могли искать `id="root"` (`webpack.config.js:22-35`). `webpack serve` — порт 3000, прокси `/console-config.json` и `/api` на `ADMIN_CONSOLE_URL` (по умолчанию `http://localhost:8082`) (`:9,36-45`).

**Старт приложения** (`UI/index.tsx:8-10`, `UI/App.tsx:12-49`) 🟢: `loadConsoleConfig()` читает `/console-config.json` (`issuer`, `clientId`, `scope`; пустое поле или не-2xx — ошибка, UI показывает «Не удалось загрузить конфигурацию консоли», `UI/auth/authService.ts:22-32`) → `createOidcAuthService(config)` → `App`: `auth.init()`; `true` → состояние `ready`, `false` → `auth.login()` (редирект на IdP), исключение → «Не удалось выполнить вход».

**Аутентификация** (`UI/auth/authService.ts:39-80`) 🟢: `oidc-client-ts` `UserManager`, Authorization Code + PKCE (`response_type: 'code'`), `redirect_uri = origin + "/"`, **пользователь и токены в `InMemoryWebStorage`** (в `sessionStorage` остаётся лишь `state`/`code_verifier` библиотеки на время редиректа). `init()`: при `?code=…&state=…` вызывает `signinRedirectCallback()`; при любой ошибке сбрасывает `code/state` из адреса (`history.replaceState`) и возвращает `false` (повторный вход); при успехе — также чистит адрес и, если `user.state` начинается с `#/`, восстанавливает маршрут. Иначе берёт пользователя из памяти. `getAccessToken()` возвращает `null`, если пользователь отсутствует или просрочен. `login()` передаёт `state = location.hash`. `logout()` определён в интерфейсе, но **нигде в UI не вызывается** (нет кнопки) 🟡.

**Маршруты** (`UI/useHashRoute.ts`, `UI/components/Layout.tsx`) 🟢: hash-роутинг `#/graph`, `#/sync`; неизвестный hash → «Граф» (`parseRoute`, `:11-14`). `App` рисует `Layout` с навигацией и `ApiProvider key={route}`: ключ пересоздаёт провайдер при смене раздела, 403 в одном разделе сбрасывается (`App.tsx:43-45`).

**Вызовы API** (`UI/api/endpoints.ts:47-72`, всё — `GET`) 🟢:

| Метод клиента | Запрос | Где используется |
|---|---|---|
| `stats()` | `GET /api/graph/stats` | `GraphSummary` |
| `search(q, types)` | `GET /api/graph/search?q=&types=…` | `AssetSearch` |
| `node(gid)` | `GET /api/graph/nodes/{gid}` | `AssetCard` |
| `neighborhood(gid, depth, relTypes)` | `GET /api/graph/nodes/{gid}/neighborhood?depth=&relTypes=…` | `NeighborhoodView` |
| `sources()` | `GET /api/sync/sources` | `SourcesTab` (автообновление 30 с) |
| `events(filter)` | `GET /api/sync/events?source&status&from&to&page&size` | `EventsTab` |
| `event(source, eventId)` | `GET /api/sync/events/{source}/{eventId}` | `EventDetailCard` |
| `dlq(p)` | `GET /api/sync/dlq?source&replayed&page&size` | `DlqTab` |
| `conflicts(p)`, `candidates(p)` | `GET /api/identity/conflicts|candidates?status&page&size` | `IdentityTab` |
| `audit(p)` | `GET /api/audit?page&size` | `AuditTab` |

Параметры кодируются `URLSearchParams` без пустых значений, массивы повторяют ключ; сегменты пути — `encodeURIComponent` (`endpoints.ts:20-32`). Клиент `createApiClient` (`UI/api/apiClient.ts:40-75`): `Accept: application/json`, `Authorization: Bearer <token>` если токен есть; 401 → `onUnauthorized` (повторный вход) + `UnauthorizedError`; 403 → `onForbidden` (экран «Нет прав») + `ForbiddenError`; другие не-2xx → `ApiError(status)`. `ApiProvider` запускает вход при 401 **один раз** на параллельные ошибки (`UI/api/ApiContext.tsx:12-37`).

**Состояние** 🟢: глобального хранилища нет. Данные — `useAsync(load, deps, refreshMs?)` (состояния `loading/ready/error`, отмена устаревшего ответа через флаг `cancelled`, тихое обновление по таймеру без сброса экрана, `UI/hooks/useAsync.ts:19-56`); сообщение об ошибке — только «Ошибка запроса (HTTP N)» или общее, тело ответа сервера в текст не попадает (`:10-13`). Поиск хранит локальное состояние и защищён счётчиком `latest` от затирания новым ответом старым (`AssetSearch.tsx:14-26`). Выбранный актив — `useState` в `GraphPage`.

**Экраны** 🟢:
- «Граф» (`UI/pages/GraphPage.tsx`): `GraphSummary` (узлы по меткам, связи по типам, «последнее обновление») → `AssetSearch` (запрос ≤ 200 символов, фильтр типов, пустой запрос не отправляется) → `AssetCard` (свойства текстом, жизненный цикл `isCurrent/firstSeenAt/lastSeenAt/deletedAt`, бейджи «закрыт» и «устарел», блок «Исходный код» для `Deployment` с `sourceCommit`, таблица записей источников со статусом `tombstone`/`активна` и расхождениями) и `NeighborhoodView` (глубина 1/2, фильтр типов связей, баннер `truncated`, `GraphCanvas` на Cytoscape с раскладкой `cose`, клик по узлу выбирает его).
- «Синхронизация» (`UI/pages/SyncPage.tsx`): вкладки «Источники» (светофор `health()`), «Журнал событий» (фильтры источник/статус/период в `datetime-local` → UTC ISO, пагинация по 50, карточка события без payload), «DLQ» (по умолчанию `replayed=false`, флажок «Включая повторённые»), «Identity» (конфликты и кандидаты, статус `OPEN`/`RESOLVED`), «Аудит» (операции ingestion, флаг «обрезано»).

### X4.2 Алгоритмы и встроенные правила

1. **Светофор источника** `health(s)` (`UI/pages/sync/SourcesTab.tsx:11-18`) 🟢: `bad`, если есть события в `QUARANTINED`/`RETRYING`, либо последний прогон `FAILED` или `failed > 0`, либо `lagSeconds > 3600`; иначе `warn`, если нет `lastProjectedAt`, нет `lastSyncRun` или `lagSeconds > 900`; иначе `ok`. Пороги — константы UI `LAG_WARN_SECONDS = 15·60`, `LAG_BAD_SECONDS = 60·60` (`UI/api/constants.ts:62-63`). Подписи бейджа: «норма» / «нет данных» / «проблемы». Автообновление раз в 30 с (`SOURCES_REFRESH_MS`).
2. **Устаревший актив** `isStale(card)` (`UI/pages/graph/AssetCard.tsx:9-13`): `isCurrent` и `lastSeenAt` старше 7 суток (`STALE_AFTER_MS`, `constants.ts:56`) 🟢.
3. **Безопасность вывода** 🟢: все значения (свойства, причины, значения конфликтов) выводятся как текст React (экранируются), `dangerouslySetInnerHTML` не используется (grep); URL репозитория показывается текстом, без ссылки (`AssetCard.tsx:15,25`); подписи узлов графа рисуются Cytoscape на canvas, HTML не интерпретируется (Javadoc `UI/components/GraphCanvas.tsx:6-9`). Это закрывает ловушку 11 на стороне клиента (тексты источников — недоверенные данные).
4. **Allowlist'ы продублированы в UI** (`UI/api/constants.ts:1-63`): `ASSET_TYPES` (7), `RELATION_TYPES` (9), `SOURCES` (4), `EVENT_STATUSES` (10) — как в `AssetTemplates`/`ConsoleTemplates`/`SyncSources`; бэкенд всё равно отвергает неизвестные значения (400). Список `RELATION_TYPES` = `NEIGHBORHOOD_RELATION_TYPES` (8 + `HOSTED_ON`) 🟢.
5. **Цвета узлов** по метке (`LABEL_COLORS`, `DEFAULT_LABEL_COLOR`) и полупрозрачность закрытых узлов (`GraphCanvas.tsx:30-50`).
6. **Сброс страницы** при смене любого фильтра журнала и DLQ (`EventsTab.tsx:64-69`, `DlqTab.tsx:27-38`); `Pager` блокирует «Назад» на первой и «Вперёд» на последней странице (`UI/components/common.tsx:34-59`).
7. **CSP-совместимость**: бандл без inline-скриптов и стилей (CSS вынесен в файл) — сверка в Java-тесте `bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles` (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:183`) 🟢.

### X4.3 Структуры данных

TypeScript-интерфейсы ответов (`UI/api/types.ts`) — зеркало Java-records `GraphResponses`/`Rows`/`Page`; сопоставление полей — `data-dictionary.md` §X.4. Состояние: `AsyncState<T>` (`loading | error | ready`), `AuthState` (`pending | ready | failed`), `Route` (`graph | sync`).

### X4.4 Константы и конфигурация

Всё в `UI/api/constants.ts`: `STALE_AFTER_MS`, `SOURCES_REFRESH_MS = 30_000`, `LAG_WARN_SECONDS`, `LAG_BAD_SECONDS`, цвета узлов; `PAGE_SIZE = 50` в каждой вкладке; `maxLength=200` поля поиска (`AssetSearch.tsx:41`). Переменных окружения в рантайме нет: конфигурация OIDC приходит с сервера (`/console-config.json`); в сборке — только `ADMIN_CONSOLE_URL` для dev-прокси.

### X4.5 Сомнения и расхождения

- 🟡 **`logout()` не подключён к UI.** Метод есть в `AuthService` и протестирован как часть интерфейса, но кнопки «Выйти» и вызова в коде нет; вход заканчивается только закрытием вкладки/истечением токена. В ADR 0026 выход не упомянут.
- 🟡 **`ApiClient.post` объявлен, но не используется** (`UI/api/apiClient.ts:37-38,73`; в страницах только `GET`) — соответствует «в UI нет изменяющих операций» (ADR 0026), но лишняя возможность оставлена в клиенте.
- 🟡 **Константы UI продублированы с бэкендом и друг с другом.** Allowlist'ы и порог `STALE_AFTER_MS` (7 суток) не связаны с `archrag.query.stale-after` (`P7D`, MCP): если порог MCP сменят, UI продолжит помечать «устарел» по 7 суткам. У консоли собственной настройки порога нет.
- 🟡 **Вкладка DLQ опирается на неработающий признак.** По умолчанию UI просит `replayed=false` («только открытые»), но `dlq_entry.replayed_at` никто не заполняет (Н1, Q5.5), поэтому флажок «Включая повторённые» ничего не меняет, а «Повтор» везде «—». Подтверждает Н1 на уровне UI.
- 🟡 **Вкладка Identity: фильтр `RESOLVED` для кандидатов практически пуст.** `identity_candidate.status` задаётся только `DEFAULT 'OPEN'` (`ingestion/identity-resolution/.../V4__identity_candidate.sql:31`) и нигде не меняется (Н2, Q3.5); для конфликтов `RESOLVED` осмыслен (`source_conflict.resolved_at`).
- 🟡 **События с `/` в `eventId` не открываются** (`encodeURIComponent` даёт `%2F`; сервер не принимает `/` в path-переменной — X3.5): ссылка в таблице журнала приведёт к ошибке карточки.
- 🟡 **Токен просрочивается без тихого обновления.** `getAccessToken()` возвращает `null` для просроченного пользователя → запрос уходит без `Authorization` → 401 → повторный вход редиректом (теряется несохранённое состояние экрана, сохраняется только `#hash`) (`authService.ts:76-77`, `ApiContext.tsx:20-28`). Поведение `automaticSilentRenew` библиотеки здесь не проверялось.
- 🟡 **Cytoscape под строгой CSP (`default-src 'self'`) проверяется только в jsdom.** Jest-тесты рисуют граф в jsdom (`data-testid="graph-canvas"`), реальное поведение под CSP проверяет только смоук платформы (`platform/docker/console-smoke.sh`, R.2e).
- 🟡 **Время:** `Time` показывает значение в локальной зоне браузера (`toLocaleString`), ISO — во всплывающей подсказке (`components/common.tsx:5-14`); `datetime-local` фильтры трактуются в локальной зоне и уходят в API как UTC (`toIso`, `:102-106`) — на границе суток может удивлять оператора в другом часовом поясе.
- 🟢 **Токен не пишется в `localStorage`/`sessionStorage`** (ADR 0026; `InMemoryWebStorage`, тест `init without code reads the in-memory user`, `authService.test.ts:42`).
- 🟢 **Ошибки сервера не раскрываются:** текст берётся из статуса, а не из тела (`describeError`, `useAsync.ts:10-13`).

---

## X5. Сомнения R.2a–R.2c, относящиеся к `access/`, — результат разбора

### X5.1 Н1 (`dlq_entry.replayed_at` не заполняется) — **подтверждено, проявляется в консоли и в UI, 🔴**

Консоль фильтрует DLQ по этой колонке (`access/admin-console/.../pg/ConsoleReadRepository.java:157-159`), а UI по умолчанию просит `replayed=false` и подписывает флажок «Включая повторённые» (`access/admin-ui/src/pages/sync/DlqTab.tsx:16-18,40-41`). Пока колонка не заполняется, «открытые» = все записи, «Повтор» = «—» у каждой, а `replayed=true` возвращает пусто. Тесты консоли и UI проверяют только передачу параметра (`DLQ shows reasons and asks only for open entries by default`, `SyncPage.test.tsx:174`), не семантику. Вопрос к архитектору (DLQ — история или очередь?) остаётся открытым.

### X5.2 Н2 (`identity_candidate.status` не меняется) — **подтверждено в UI, 🔴**

Вкладка Identity предлагает статусы `OPEN`/`RESOLVED` для кандидатов (`access/admin-ui/src/pages/sync/IdentityTab.tsx:9,71`), хотя в БД статус задаётся только `DEFAULT 'OPEN'` (`ingestion/identity-resolution/.../V4__identity_candidate.sql:31`) и после crosswalk не меняется. `RESOLVED` для кандидатов всегда пуст; для конфликтов (`source_conflict`) фильтр осмыслен. Консоль честно отдаёт то, что есть в таблице; расхождение — в самом ingestion.

### X5.3 Н7 (checkpoint `projector` читает только консоль) — **уточнено, 🟡**

Консоль читает **все** строки `consumer_checkpoint` (`ConsoleReadRepository.java:55`) и возвращает их в `SourceStatus.checkpoints` (`SyncController.java:71-72`), то есть Н7 верна: единственный читатель — `access/admin-console`. Но UI **не показывает** checkpoints: колонки вкладки «Источники» — источник, состояние, последний SyncRun, lag, события по статусам, ошибки (`access/admin-ui/src/pages/sync/SourcesTab.tsx:39-47`); значение `checkpoints` оператору нигде не выводится и в `health()` не участвует (`:11-18`). Фактически читатель — только сырой JSON API.

### X5.4 Н8 / Q6.1 / A5 (копии таблицы кодов источников) — **подтверждено, найдены новые копии в `access/`, 🟡**

К четырём копиям из Q6.1 добавляются:
5. `SyncSources.CODES = [eam, scm, cmdb, deploymap]` + `URN_PREFIX` (`access/admin-console/.../pg/SyncSources.java:16,19`) и `ADAPTERS = CODES.toUpperCase()` для фильтра `SyncRun.adapter` (`SyncController.java:37`);
6. `SOURCES = ['eam','scm','cmdb','deploymap']` в UI (`access/admin-ui/src/api/constants.ts:27`).
Верхний регистр `ADAPTERS` согласован с тем, что пишет проектор: `SyncRun.adapter = SourceKey.source().name()` — имя `SourceSystemCode` (`ingestion/graph-projector/.../GraphProjector.java:697-698,715`, `contracts/canonical-model/.../SourceSystemCode.java:5`). Теста, который это связывает, нет: перепутанный регистр дал бы пустой `lastSyncRun` без ошибки. В ответах разных API источник пишется по-разному: `eam|scm|cmdb|deploymap` (журнал, консоль), `EAM|SCM|CMDB|DEPLOYMAP` (`SourceRecord.source` в графе → `sources`, MCP, карточка актива) — оператор видит обе формы в соседних экранах (A5, `docs/03-interfaces.md` §8 п.8).

### X5.5 `SyncRun` без жизненного цикла — **подтверждено документом, влияет на консоль и UI, 🟡/🔴**

Проектор создаёт `SyncRun` с `status = 'RUNNING'` и `startedAt = min(время событий)`; `endedAt`, `fetched`, `applied`, `failed` не пишутся, перевода в `SUCCEEDED`/`FAILED` нет нигде в `src/main` (grep по `endedAt|SyncRunStatus|"FAILED"` вне `AdminAudit`) (`GraphProjector.java:687-704`). Документы это признают (`docs/02-data-model.md:59,357,362`, `docs/03-interfaces.md:410`). Последствия для `access/`: `console_latest_sync_runs` всегда отдаёт `RUNNING` и `null`-счётчики (`ConsoleTemplates.java:159-179`); ветка `failedRun` в `health()` (`status === 'FAILED'` или `failed > 0`, `SourcesTab.tsx:13`) **недостижима**, светофор опирается только на события и lag. Решение «писать ли итоговый статус» — за архитектором.

### X5.6 Q6.5 / Н (`datetime == null → Instant.EPOCH`) — **влияние на `access/`, 🟡 (выведено)**

Проектор пишет `lastSeenAt = max(lastSeenAt, eventTime)` и `firstSeenAt = min(…)` (`GraphProjector.java:458-459`). Узел, у которого единственным источником был объект без `datetime` (EPOCH, Q6.5), получит `lastSeenAt = 1970-01-01`. Тогда `trace_dependencies` и `find_runtime_footprint` пометят его `stale: true` (`TraceDependenciesTool.java:187-190`, `FindRuntimeFootprintTool.java:200-202`), UI — «устарел» (`AssetCard.tsx:9-13`), а `Time` покажет 01.01.1970. Для MCP-клиента это выглядит как «давно не виден», хотя причина — отсутствие `datetime` в источнике.

### X5.7 Прочее, что подтверждено или снято

- 🟢 **Q4 «`access/*` не использует `graph-projector` и не получает writer-доступ»** — подтверждено: `access/graph-query-core/pom.xml` → только `canonical-model`; `mcp-server` → `graph-query-core`; `admin-console` → `admin-ui`, `graph-query-core` (`pom.xml` модулей). В конфигурации приложений — только `archrag.neo4j.reader.*` (`access/mcp-server/src/main/resources/application.yml:36-41`, `access/admin-console/src/main/resources/application.yml:25-29`); в консоли дополнительно PG-роль `archrag_console_ro` (SELECT, V6). Ловушка 3 соблюдена, ловушка 1 тоже: ни одного динамического Cypher из ввода.
- 🟡 **`mcp-server` использует `canonical-model` без явной зависимости.** `ExplainProvenanceTool` импортирует `AuthorityMatrix`, `NodeLabel`, `SourceSystemCode` (`MCP/tools/ExplainProvenanceTool.java:3-5,111-117`), но `access/mcp-server/pom.xml` объявляет только `graph-query-core`; доступ идёт транзитивно. Схема зависимостей не нарушена (`contracts` разрешён), но при смене зависимостей `graph-query-core` сборка модуля сломается неочевидно.
- 🟡 **`ASSERTS.confidence` всегда `1.0`.** Проектор записывает `a.confidence = 1.0` константой (`GraphProjector.java:462`), поэтому поле `confidence` в `explain_provenance` и в карточке консоли не несёт информации; `authority` и `conflicts` — настоящие. Выведено из кода записи; проверка по `docs/02` — R.3.
- 🟢 **Три состояния узла на стороне чтения согласованы с ADR 0020 (tombstone вместо hard delete).** `search_assets`, `trace_*`, `runtime_footprint` фильтруют `isCurrent = true`; `get_asset`, `explain_provenance` отдают закрытые узлы и неактивные записи с `deletedAt` (`AssetTemplates.java:107-110,175-178`). `access/` не предполагает физического удаления.
- 🟢 **R.2a A5 «`SourceRecord` не содержит `deletedAt`»** — со стороны чтения поле используется: `explain_provenance` читает `r.deletedAt` (`AssetTemplates.java:199`), UI показывает бейдж `tombstone` (`AssetCard.tsx:101-102`). Подтверждает, что поле живёт в графе, а не в Java-типе.
- 🟡 **Имена свойств, которые читают шаблоны, согласованы с записью проектора (выборочная сверка).** `eamCode`, `isCurrent`, `firstSeenAt`, `lastSeenAt`, `deletedAt`, `contentHash`, `assertedBySource/Type/Id`, `validTo`, `authority`, `confidence`, `conflicts` присутствуют в `GraphProjector`/`canonical-model`; полный словарь и контрольная сверка — R.3.
- 🟡 **`STREAMABLE` против `STATELESS`:** инструкции окружения агента упоминают `STREAMABLE`, код и ADR 0017 (уточнение UNLOCKER-224) — `STATELESS` (`access/mcp-server/src/main/resources/application.yml:11`). Расхождение закрыто документами; сомнение Scout (`inventory.md` п.3) подтверждено как «осознанное».

---

## X6. Итоги по модулям R.2d

| Модуль | Java-файлов main / строк | Тестов | Ключевые алгоритмы | Основные риски (см. §) |
|---|---|---|---|---|
| `graph-query-core` | 14 (+2 `package-info`) / ≈1270 | 47 в 8 классах (executor — только IT) | статическая валидация шаблона, clamp бюджета, EXPLAIN-кэш, сбор строк с лимитом строк/байт | X1.6: executor без unit-теста, фильтр типов связей после раскрытия, порядок путей не определён |
| `mcp-server` | 24 (+3) / ≈2150 | 100 в 16 классах | scope на `tools/call` до диспетчеризации, аспект аудита, сборка путей и `stale`, группировка footprint | X2.5: `systemGid` не нормализован, один scope на всё, недоверенный текст в `properties` |
| `admin-console` | 25 (+3) / ≈1775 | 31 в 3 классах (SQL — только IT) | WHERE из константных фрагментов, пагинация, SPA fallback, расчёт lag | X3.5: значения конфликтов отдаются, копипаст с MCP, `eventId` с `/` |
| `admin-ui` | 26 TS/TSX / ≈1730 | 53 в 6 файлах Jest | PKCE с токеном в памяти, `useAsync`, светофор источника | X4.5: `logout` не подключён, константы продублированы, DLQ/Identity опираются на незаполняемые поля |

Сквозное по группе:
- **Read-only доказывается в трёх слоях** (статические шаблоны + allowlist; `EXPLAIN → READ_ONLY`; `AccessMode.READ` с серверным отказом `Statement.AccessMode`) — IT `GraphQueryCoreIT` и `AdminConsoleIT` (`everyConsoleTemplatePassesReadOnlyExplainAndWritingTemplateIsClosed`, `platform/integration-tests/.../AdminConsoleIT.java:280`) (X1.2 п.4).
- **Три контура аутентификации** с разными audience и scope: MCP (`architecture.read`, audience = `ARCHRAG_MCP_RESOURCE_URI`), консоль (`architecture.admin`, `urn:archrag:console`), admin REST ingestion (`architecture.admin` + `azp`) — токены не взаимозаменяемы по `aud`.
- **Открытые вопросы к архитектору** (🔴/🟡, не блокируют): Н1 (смысл DLQ), Н2 (статусы кандидатов), `SyncRun` без итогового статуса (X5.5), нормализация `systemGid` в `find_runtime_footprint` (X2.5), разграничение scope tools (X2.5), кто и когда показывает `checkpoints` оператору (X5.3).
- **Не проверялось в этом этапе** (передано в R.2e/R.3): фактическая работа IT с Neo4j/PG (`platform/integration-tests`), Keycloak-клиенты и CSP в смоуке (`platform/docker`), полный словарь меток и свойств графа.
