# Code Analysis → Технический анализ кода

> Archaeologist, этапы R.2a (UNLOCKER-263: `contracts/*`, разделы A, E, S), R.2b (UNLOCKER-264: `adapters/*`, раздел R.2b / P) и R.2c (UNLOCKER-265: `ingestion/*`, раздел «R.2c — `ingestion/*`», Q1–Q7, в конце файла).
> R.2d (`access/*`, X1–X6) и R.2e (`platform/*`, Y1–Y2, Z1, W1) добавлены в конце файла.
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
Состав: 38 файлов main — пакеты `node` (19), `relation` (2), `command` (6), `provenance` (7), `authority` (1), корень (`Invariants`, маркер, `package-info`). Тесты: 45 `@Test` и 3 `@ParameterizedTest` (все три в `GitUrlsTest`) в 7 классах 🟢.

### A1. Поток управления и публичные операции

Модуль не содержит сервисов; «поток» — это конструкторные проверки и несколько чистых функций.

| Операция | Что делает | Где |
|---|---|---|
| `Invariants.requireText / optionalText` | обязательный и необязательный текст; пустая строка у необязательного **отвергается** (`null` — «не передано») | `CM/Invariants.java:21-35` 🟢 |
| `Invariants.copyProperties` | проверка и неизменяемая копия карты свойств связи | `CM/Invariants.java:49-58` 🟢 |
| `NodeLabel.isA` | транзитивная проверка подтипа по цепочке `supertype` | `CM/node/NodeLabel.java:54-61` 🟢 |
| `NodeLabel.canonical()` | все метки, кроме провенанс-меток | `CM/node/NodeLabel.java:68-71` 🟢 |
| `RelationType.allows(from, to)` | пара меток допустима с учётом подтипов | `CM/relation/RelationType.java:73-75` 🟢 |
| `AuthorityMatrix.isAuthoritative(...)` ×3 | авторитетность источника для узла / свойства / связи | `CM/authority/AuthorityMatrix.java:48-60` 🟢 |
| `GitUrls.normalize` | приведение URL репозитория к канону | `CM/node/GitUrls.java:30-74` 🟢 |
| `ComputeInstance.label()` | выбор метки по `kind` (`switch`) | `CM/node/ComputeInstance.java:35-41` 🟢 |
| `LookupKeys.repositoryUrl / itSystemCode` | фабрики ключей-псевдонимов для отложенных связей | `CM/provenance/LookupKeys.java:20-30` 🟢 |

Обработка ошибок: единственный механизм — исключения конструкторов; ни одного `catch` с проглатыванием. В `GitUrls.normalize` `URISyntaxException` перехватывается **без** `cause`, чтобы вход (с возможными учётными данными) не попал в сообщение (`CM/node/GitUrls.java:47-52`) 🟢 — выполнение ловушки 12.

### A2. Алгоритмы и встроенные правила

1. **Иерархия меток.** `VIRTUAL_MACHINE` и `PHYSICAL_SERVER` — подтипы `COMPUTE_INSTANCE` (`NodeLabel.java:23-24`); остальные метки плоские 🟢. Глубина > 1 поддержана циклом `isA`, но в данных не используется 🟡.
2. **Допустимость связи.** `sources.stream().anyMatch(from::isA) && targets.stream().anyMatch(to::isA)` (`RelationType.java:74`) 🟢. Пример: `RUNS_ON` объявлена на `COMPUTE_INSTANCE`, поэтому принимает и `VirtualMachine`, и `PhysicalServer`.
3. **Темпоральность и identity-свойства.** 10 из 15 типов связей `temporal=true` (`RelationType.java:13-27`); identity-свойства допустимы только у темпоральных, иначе `IllegalArgumentException` при инициализации enum (`RelationType.java:42-44`). Сейчас identity-свойство одно — `DEPLOYED_FROM{commit}` (ADR [0029](../docs/adr/0029-helm-chart-system-repo-commit.md)); смена такого свойства закрывает старую связь и открывает новую (`RelationType.java:48-52`) 🟢.
4. **Authority matrix.** Поиск точный и fail-closed: нет записи — пустое множество (`AuthorityMatrix.java:63-75`); свойство без собственного правила наследует правило метки, суперметка не опрашивается (`AuthorityMatrix.java:68-70`) 🟢. `MANUAL` не входит ни в одно правило (`AuthorityMatrix.java:79-113`) — «ручное разрешение» вынесено в отдельное утверждение (Javadoc `AuthorityMatrix.java:20-22`) 🟢.
5. **Нормализация Git URL** (`GitUrls.normalize`): scp-форма `git@host:org/repo` и схемы `ssh`/`git`/`https` → `https`; `http` остаётся `http`; порт сохраняется только для `http(s)`; схема и хост в нижнем регистре, путь — как есть; удаляются `.git` и хвостовые `/`; прочие схемы → `IllegalArgumentException("unsupported git url scheme")` (`GitUrls.java:40-73`, `76-91`) 🟢. Идемпотентность нормализации используется как инвариант `Deployment.repositoryUrl` (`Deployment.java:30-32`) 🟢.
6. **Валидация `Deployment`.** `sourceCommit` — ровно 40 hex в нижнем регистре; `eamSystemId > 0` (`Deployment.java:20, 27-35`) 🟢.
7. **Валидация `ComputeInstance`.** `hypervisorRef` только у VM, `serialNumber` только у `PHYSICAL_SERVER` (`ComputeInstance.java:26-31`) 🟢.
8. **Lifecycle.** `firstSeenAt ≤ lastSeenAt` и `isCurrent ⇔ deletedAt == null` (`Lifecycle.java:17-20`) 🟢.

### A3. Структуры данных

Полная таблица полей — в `data-dictionary.md` (раздел A). Кратко:

- `NodeData` — sealed-интерфейс с **11** вариантами (`NodeData.java:9-11`); `CanonicalNode<T extends NodeData>(gid, data, lifecycle)` — «хранимый» узел (`CanonicalNode.java:7`) 🟢.
- `GraphCommand` — sealed-интерфейс с **5** вариантами: `UpsertNode`, `UpsertRelation`, `TombstoneSourceRecord`, `DeferRelation` (`GraphCommand.java:7-8`) 🟢 — совпадает с `docs/02-data-model.md:193`.
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

- `JournalEntry` — 15 полей, **нет** `type`/`subject`; они вынесены в `StoredEvent(entry, type, subject)` (`ES/JournalEntry.java:14-29`, `ES/StoredEvent.java:12`) 🟢.
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
| `canonical-model` | 38 | 36 | 11 `NodeData`, 4 `GraphCommand`, 15 `RelationType` | A5: `SourceSystem` ×3, `platformId` в `docs/02` |
| `event-schemas` | 18 | 16 | 4 порта, 10 статусов | E5: типы событий вне контракта, нет валидации `JournalEntry`, разнобой NPE/IAE |
| `source-spi` | 10 | 9 | 1 порт, 1 утилита подписи | S5: инварианты `SourceChange` только в Javadoc, `long` vs `SourceVersion` |

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

**`GraphProjector.project`** (`GP/GraphProjector.java:106-111, 302-359`): `session.executeWrite(tx -> write(tx, request))` внутри `try-with-resources`; драйвер — один bean (ловушки 7, 8). Шаги `write`: (1) `MERGE (r:SourceRecord {source,sourceType,sourceId}) SET r._lock = true REMOVE r._lock` — блокировка записи до чтения версии (`:305-310`); (2) `VersionDecision` → `NOOP_SAME_VERSION`/`IGNORED_OLD_VERSION` без записи (`:312-324`); (3) `PendingRelations.deleteOf` — отложенные связи записи заменяются целиком (`:328`); (4) команды: `UpsertNode` (+ `healPending`, `healAliases`), `DeferRelation`, `TombstoneSourceRecord`, `UpsertRelation` (`:329-349`); (5) `closeReplacedRelations` (не для tombstone), `markRelationOnlyRecord`, `writeConflictMarkers`, `linkSyncRun` (`:350-357`) 🟢.

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
> Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Ссылки `файл:строка` — от корня репозитория. Skill `reversa-archaeologist` v1.1.0: в runtime этого прогона он не установлен (нет в `.agents/` и `.claude/skills/` рабочей копии), поэтому использована копия `SKILL.md` версии 1.1.0 из рабочей области другого прогона на той же машине; источники R.2b и R.2c в specs не зафиксированы, их совпадение с этой копией не проверено.
> Не разбирается: `platform/*` (R.2e), полная схема Neo4j и Flyway (R.3), сторонние библиотеки `admin-ui`.

## X1. `access/graph-query-core`

Сокращения: `GQC` = `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore`, `GQT` = `GQC/templates`.

**Назначение.** Единственный путь чтения графа для `mcp-server` и `admin-console`: реестр именованных Cypher-шаблонов, бюджет запроса и исполнитель `GraphQueryExecutor`, который принимает **ID шаблона**, а не текст Cypher (`GQC/GraphQueryExecutor.java:23-31,60-64`) 🟢. Зависит только от `canonical-model`, Neo4j Java Driver и Jackson 3 (подсчёт байт строки) (`access/graph-query-core/pom.xml:14-26`); `ingestion/*` и `adapters/*` не подключены, writer-учётных данных у модуля нет — они приходят из вызывающего приложения как готовый `Driver` 🟢. Состав: 14 Java-файлов main (+2 `package-info`), ≈1270 строк; тестов 47 `@Test` в 8 классах (`BudgetedCollectorTest` 6, `QueryTemplateRegistryTest` 13, `ResultBudgetTest` 3, `FulltextQueryTest` 3, `AssetTemplatesTest` 3, `ConsoleTemplatesTest` 4, `DependencyTemplatesTest` 9, `RuntimeTemplatesTest` 6). **`GraphQueryExecutor` в самом модуле не тестируется** — только IT `platform/integration-tests/.../GraphQueryCoreIT.java` (R.2e) 🟡.

### X1.1 Поток управления

`GraphQueryExecutor.execute(templateId, params, budget)` (`GQC/GraphQueryExecutor.java:60-97`) 🟢:
1. `registry.find(id)`, иначе `IllegalArgumentException("Unknown template")` (`:61-64`).
2. `checkParameters`: набор ключей `params` должен **в точности** совпасть с `template.parameters()`; лишний или недостающий ключ → `IllegalArgumentException` (`:133-146`). Параметр `limit` вызывающему недоступен: он зарезервирован (`QueryTemplate.java:53-56`) и добавляется исполнителем (`:72`).
3. Бюджет: запрошенный (или потолки целиком, если `null`) режется `clampTo(limits)` до потолков конфигурации (`:67`, `ResultBudget.java:30-37`).
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
3. **Бюджет**: `ResultBudget` отвергает неположительные значения и нулевой/отрицательный `timeout` (`GQC/ResultBudget.java:20-27`); `QueryLimits` создаёт `ResultBudget` в компактном конструкторе, чтобы потолки подчинялись тем же инвариантам (`QueryLimits.java:17-20`). `clampTo` берёт минимум по каждому полю, таймаут — меньший из двух (`ResultBudget.java:30-37`) 🟢. `maxPaths` — потолок строк для шаблонов `PATHS`; Javadoc называет его «резервируемым для шаблонов обхода» (`ResultBudget.java:13`), фактически он применяется (`ResultKind.maxRows`) 🟢.
4. **Read-only защита в три слоя** 🟢: (а) статика шаблонов + allowlist меток/типов связей; (б) `EXPLAIN → READ_ONLY` один раз на шаблон; (в) `AccessMode.READ` и `executeRead` (`GQC/GraphQueryExecutor.java:35-36,82`). Слой (в) **проверен как серверный отказ**: `GraphQueryCoreIT.readSessionConfigRejectsWritesAtDatabaseLevel` ждёт `Neo.ClientError.Statement.AccessMode` на `CREATE` в read-транзакции под пользователем без ролей (`platform/integration-tests/.../GraphQueryCoreIT.java:111-120`); `writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed` доказывает слой (б) (`:78-100`).
5. **Allowlist меток и типов связей** подставляется в текст из констант классов, не из ввода: `AssetTemplates.SEARCHABLE_TYPES` (7 меток, `GQT/AssetTemplates.java:16-17`), `RELATION_TYPES` (8 типов, `:102-103`), `DependencyTemplates.TRACE_LABELS` (11 меток, `GQT/DependencyTemplates.java:22-25`), `TRACE_RELATION_TYPES` (7, `:18-19`), `IMPACT_RELATION_TYPES` (5, `:98-99`), `ConsoleTemplates.STATS_LABELS` (= `TRACE_LABELS` + `SourceRecord`, `:19-25`), `STATS_RELATION_TYPES` (= `RELATION_TYPES` + `HOSTED_ON`, `ASSERTS`, `:28-35`), `NEIGHBORHOOD_RELATION_TYPES` (= `RELATION_TYPES` + `HOSTED_ON`, `:38`). Значения, приходящие от вызывающего (`relationTypes`, `relTypes`, `types`), передаются параметрами и сверяются вызывающим по этим спискам (Javadoc `DependencyTemplates.java:11-13`); сам `graph-query-core` членство **не проверяет** 🟡 (проверка в `TraceDependenciesTool`, X2, и `GraphController`, X3).
6. **Свежесть и темпоральность в запросах.** Открытая связь = `validTo IS NULL`; текущий узел = `isCurrent = true` — фильтры внутри шаблонов (`DependencyTemplates.java:29-33`, `RuntimeTemplates.java:33-41`, `ConsoleTemplates.java:115-116,145`). Исключения: `get_asset` и `explain_provenance` отдают закрытые узлы (`GQT/AssetTemplates.java:107-109,174-175`).
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

**Назначение.** Spring Boot-приложение MCP-сервера: Streamable HTTP на `POST /mcp` в режиме `STATELESS`, 6 tools (из них 5 предметных и служебный `ping`), OAuth2 resource server (JWT: issuer + audience), scope на каждый `tools/call`, аудит и метрики каждого вызова, чтение графа только через `graph-query-core` (`access/mcp-server/src/main/resources/application.yml:11-13,19-20`; `MCP/McpServerApplication.java:6-8`) 🟢. Зависимости: `graph-query-core`, `neo4j-java-driver`, `spring-ai-starter-mcp-server-webmvc`, actuator, Micrometer OTLP, AspectJ, `oauth2-resource-server` (`access/mcp-server/pom.xml`); `ingestion/*` и `adapters/*` не подключены, writer-свойств Neo4j нет — только `archrag.neo4j.reader.*` (`access/mcp-server/src/main/resources/application.yml:36-41`) 🟢. Состав: 24 Java-файла main (без `package-info`), ≈2150 строк; тестов 100 `@Test` в 16 классах: tools — 39 (`TraceDependenciesToolTest` 15, `ExplainProvenanceToolTest` 8, `FindRuntimeFootprintToolTest` 6, `GetAssetToolTest` 6, `SearchAssetsToolTest` 4), через HTTP `@SpringBootTest` — 44 (`McpSecurityTest` 13, `McpAuditTest` 9, `TraceDependenciesMcpTest` 8, `McpServerInteropTest` 4, `GetAssetMcpTest` 4, `SearchAssetsMcpTest` 3, `FindRuntimeFootprintMcpTest` 3), прочее — 17 (`ToolArgumentsTest` 7, `ToolCallAuditorTest` 5, `GraphReaderConfigurationTest` 3, `ActuatorProbesTest` 2). Neo4j в тестах модуля не поднимается: драйвер ленивый, `GraphQueries` подменяется (`access/mcp-server/src/test/resources/config/application.yml:1-7`) 🟡 — сквозное чтение графа проверяют IT платформы (R.2e).

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
- 🟡 **Недоверенный текст в ответах.** `get_asset` отдаёт `properties(n)` целиком (`access/graph-query-core/.../templates/AssetTemplates.java:119`, `GetAssetTool.java:104-114`) — включая любые текстовые поля источников (описания и т. п.) без пометки «недоверенные данные»; на стороне сервера они не интерпретируются, но MCP-клиент (LLM) получит их как обычный JSON. Ловушка 11 про логи соблюдена (аргументы усекаются, значения свойств не логируются), про выдачу агенту — решения в коде нет.
- 🟡 **`tools/list` и `initialize` доступны токену без scope.** Это зафиксировано тестом (`McpSecurityTest.java:96`) и описывает метаданные tools (имена, описания, схемы) любому владельцу валидного токена с нужным audience.
- 🟡 **Ограничение аудита: ThreadLocal.** Tool, исполняющий запросы в другом потоке, не попадёт в `templates`/`rows` (Javadoc `ToolCallContext.java:7-13`). Сейчас все tools синхронны.
- 🟡 **Описание `search_assets` и шаблон.** Описание tool говорит «точное совпадение по gid или sourceId» (`SearchAssetsTool.java:47-50`), шаблон дополнительно ищет `ITSystem.eamCode` (`access/graph-query-core/.../templates/AssetTemplates.java:48-50`); fulltext-индекс `asset_text` построен только по `ITSystem|Service` (`ingestion/graph-projector/.../Neo4jSchema.java:57`) — описание это отражает.
- 🟡 **`get_asset`: `truncated` только по связям** (`GetAssetTool.java:75-91`); обрезка карточки (`sources`) бюджетом ответа в флаге не видна — шаблон возвращает одну строку с вложенным списком, при превышении `max-response-bytes` строка будет отброшена целиком, и вызов закончится `asset not found` 🟡 (поведение выведено из `BudgetedCollector.java:51-55` и `GetAssetTool.java:69-71`, тестом не подтверждено).
- 🟡 **Дубликат `AuthorityMatrix.defaults()`.** `ExplainProvenanceTool` создаёт собственный экземпляр (`:28`), как и два места в `ingestion-service` (Q5.5). Таблица одна (`canonical-model`), а экземпляров три — при смене правил менять придётся только `defaults()`, но кастомизации по конфигурации нет.
- 🟢 **ADR 0017 / `STATELESS`.** Код использует `protocol=STATELESS`, что отражено в ADR 0017 (уточнение 2026-10-07, UNLOCKER-224) и `docs/03-interfaces.md` §8 п.1; формулировка `STREAMABLE` в `docs/00-vision.md:555` устарела относительно ADR — расхождение закрыто документами, не кодом.
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

1. **Светофор источника** `health(s)` (`UI/pages/sync/SourcesTab.tsx:11-18`) 🟢: `bad`, если есть события в `QUARANTINED`/`RETRYING`, либо последний прогон `FAILED` или `failed > 0` (🟢 [UNLOCKER-280: реализовано] обе ветки достижимы: `EventProcessor` закрывает `SyncRun` по маркеру, `RUNNING` — «идёт прогон», `warn`; `admin-ui/questions.md` Q-01), либо `lagSeconds > 3600`; иначе `warn`, если нет `lastProjectedAt`, нет `lastSyncRun` или `lagSeconds > 900`; иначе `ok`. Пороги — константы UI `LAG_WARN_SECONDS = 15·60`, `LAG_BAD_SECONDS = 60·60` (`UI/api/constants.ts:62-63`). Подписи бейджа: «норма» / «нет данных» / «проблемы». Автообновление раз в 30 с (`SOURCES_REFRESH_MS`).
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
- 🟢 **Три состояния узла на стороне чтения согласованы с ADR 0020 (tombstone вместо hard delete).** `search_assets`, `trace_*`, `runtime_footprint` фильтруют `isCurrent = true`; `get_asset`, `explain_provenance` отдают закрытые узлы и неактивные записи с `deletedAt` (`AssetTemplates.java:107-109,174-175`). `access/` не предполагает физического удаления.
- 🟢 **R.2a A5 «`SourceRecord` не содержит `deletedAt`»** — со стороны чтения поле используется: `explain_provenance` читает `r.deletedAt` (`AssetTemplates.java:199`), UI показывает бейдж `tombstone` (`AssetCard.tsx:101-102`). Подтверждает, что поле живёт в графе, а не в Java-типе.
- 🟡 **Имена свойств, которые читают шаблоны, согласованы с записью проектора (выборочная сверка).** `eamCode`, `isCurrent`, `firstSeenAt`, `lastSeenAt`, `deletedAt`, `contentHash`, `assertedBySource/Type/Id`, `validTo`, `authority`, `confidence`, `conflicts` присутствуют в `GraphProjector`/`canonical-model`; полный словарь и контрольная сверка — R.3.
- 🟡 **`STREAMABLE` против `STATELESS`:** `docs/00-vision.md:555` называет `STREAMABLE`, код и ADR 0017 (уточнение UNLOCKER-224) — `STATELESS` (`access/mcp-server/src/main/resources/application.yml:11`). Расхождение закрыто документами; сомнение Scout (`inventory.md` п.3) подтверждено как «осознанное».

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

---

# R.2e — `platform/*` (UNLOCKER-267)

> Archaeologist, этап R.2e: `platform/source-stubs`, `platform/demo-launcher`, `platform/integration-tests`, `platform/docker`. Разделы Y1–Y2 (заглушки и запуск), Z1 (IT-набор), W1 (compose-стенд). Схема Neo4j, Flyway и S3-ключи — R.3, правила и retroactive ADR — R.4. Тесты и Docker в этом прогоне не запускались: описано, что тесты и скрипты утверждают, а не результат прогона.

Сокращения путей: `SS` = `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs`, `DL` = `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher`; тесты: `SST` = `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs`, `DLT` = `platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher`. Остальные пути даны от корня репозитория. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение.

Заглушки — решение ADR [0010](../docs/adr/0010-master-systems-are-stubs.md) («Адаптеры работают с заглушками, повторяющими зафиксированные контракты»); REST-контракт и сценарии описаны в `docs/03-interfaces.md` §5.5 (`:521-550`), EAM API — в `docs/06-eam-integration.md` (раздел «Заглушки и fixtures», `:125-129`) и `docs/eam-api.yaml`. Форматы `DeployMapFormat` / `EamApiFormat` разобраны в P7 (`code-analysis.md`, §P7.1–P7.3) и здесь не повторяются: только то, чего там нет.

---

## Y1. `platform/source-stubs`

**Назначение.** Библиотека «мастер-систем» для PoC: (а) управляемая in-memory заглушка любого из четырёх источников по `SourceConnector` (`StubSource`) с HTTP-фасадом `GET /changes`, `GET /objects/{type}/{id}` (`StubSourceServer`); (б) отдельная заглушка реального EAM API `ArchObject` (`EamApiStore` + `EamApiStub` + `EamApiSeed` + `EamApiTypeMeta`); (в) начальные данные и негативные сценарии (`StubSources`); (г) отправитель подписанных webhook (`StubWebhookSender`); (д) собственный мини-парсер JSON (`Json`) (`SS/package-info.java:1-5`) 🟢. Модуль не часть прод-контура (`docs/03-interfaces.md:525`). Зависит только от `source-spi` (`platform/source-stubs/pom.xml:17-22`); без `src/main/resources` и без `*IT` 🟢. Потребители: в `test`-scope — `adapter-core`, `eam-adapter`, `scm-adapter`, `asset-adapter`, `deploymap-adapter`, `normalizer`, `platform/integration-tests`; в основном scope — `demo-launcher` (`platform/demo-launcher/pom.xml:50-53`) 🟢.

### Y1.1 Состав

| Файл | Строк | Что внутри |
|---|---|---|
| `SS/StubSource.java` | 214 | `StubSource implements SourceConnector`: журнал изменений, версии, snapshot/инкремент, сбои, webhook-события |
| `SS/StubSourceServer.java` | 187 | JDK `HttpServer` над `StubSource`: `/changes`, `/objects/`, расписание сбоев по номеру вызова |
| `SS/StubSources.java` | 221 | seed четырёх источников, константы типов, хелперы негативных сценариев, `fields(...)` |
| `SS/DeployMapFormat.java` | 48 | константы временного формата deploy map (P7.1) |
| `SS/EamApiStore.java` | 97 | хранилище `ArchObject`: тип → id → `Entry`, `ver`, архив, страница с сортировкой |
| `SS/EamApiStub.java` | 240 | `HttpServer` формата реального EAM: `/api/<type>/`, `/api/<type>/{id}/`, `/api/types/**`, токен |
| `SS/EamApiSeed.java` | 69 | seed `EamApiStore` (6 объектов), id типов, константы |
| `SS/EamApiTypeMeta.java` | 62 | метаданные типов для `/api/types/{id}/options/` |
| `SS/EamApiFormat.java` | 83 | разбор и проверка `ArchObject` (P7.3) |
| `SS/StubWebhookSender.java` | 96 | подписанный webhook на endpoint адаптера, негативные варианты |
| `SS/Json.java` | 234 | `write`/`parse` JSON без библиотек, package-private |
| `SS/package-info.java` | 6 | Javadoc пакета |

Всего 12 main-файлов, 1557 строк. Тесты: 5 классов, 48 `@Test`, 713 строк (Y1.6) 🟢.

### Y1.2 `StubSource` — журнал, версии, курсоры

**Состояние.** `journal: List<Entry(seq, change, notified)>`, `versions: Map<"type/id", Long>`, флаги `webhooksSuppressed`, `reorderNextPage`, счётчик `failuresLeft` со `status`/`retryAfter` (`SS/StubSource.java:31-41`). Все публичные методы `synchronized` (класс потокобезопасен, `:29`) 🟢.

**Запись.** `upsert` → `COMPLETE`, `upsertPartial` → `PARTIAL` (в payload только переданные поля, значения могут быть `null`), `delete` → `DELETE` + `COMPLETE` + пустой payload (`:54-70`). Все три идут через `append` (`:182-190`): `sourceVersion = versions.merge(type/id, 1, sum)` — **версия монотонна на ключ и растёт также на `DELETE`**, повторный upsert после delete продолжает нумерацию; `updatedAt = clock.instant()` (UTC `Instant`, ловушка 10); `seq = journal.size() + 1`; `notified = !webhooksSuppressed` 🟢. `SourceChange` копирует payload в `LinkedHashMap`, сохраняя `null`-значения (`contracts/source-spi/.../SourceChange.java:39-42`) — именно на этом держится сценарий «неполная запись не затирает» (ловушка 5; тест `SST/StubSourceTest.java:154`) 🟢.

**`fetchChanges(cursor, limit)`** (`:108-168`) 🟢:
1. `limit ≤ 0` → `IllegalArgumentException` (`:109-111`); затем `failIfRequested()` (`:112`) — счётчик сбоев уменьшается только на допустимом вызове.
2. Курсор `i<seq>` — **инкремент** (`:113-131`): обход всего журнала, в страницу берутся записи с `seq > after`, не больше `limit`. Если взведён `reorderNextPage` и страница не пуста — флаг сбрасывается, страница разворачивается и в конец добавляется дубль первой записи (`:123-128`). Ответ: `ChangePage(page, "i"+last, hasMore = journal.size() > last, snapshotComplete = false)` (`:130`).
3. `cursor == null` — **начало snapshot**: `bound = journal.size()`, `after = 0` (`:134-136`); `s<after>:<bound>` — продолжение (`:137-140`); иной префикс → `IllegalArgumentException("unknown cursor")` (`:141-143`).
4. Snapshot (`:144-167`): из записей с `seq ≤ bound` по ключу `type/id` остаётся последняя (`byKey`), из них только `UPSERT` → `latest: TreeMap<seq, change>`; страница — первые `limit` элементов с `seq > after`. `more` = есть ли в `latest` ещё `seq > last`. Следующий курсор: `more ? "s"+last+":"+bound : "i"+bound`; `snapshotComplete = !more` (`:164-167`).

Следствия: объект, изменённый после `bound`, в этот snapshot не попадает и приходит инкрементом с курсором `i<bound>`; удалённый объект в snapshot отсутствует (основа «удалять можно только после полного snapshot», ловушка 9) 🟢 (тесты `SST/StubSourceTest.java:94,108`). Snapshot отдаёт **последнюю** запись ключа как есть — если она `PARTIAL`, объект приходит с `completeness=PARTIAL` 🟡 (поведение не зафиксировано тестом).

**`fetchById`** (`:171-180`): последняя запись журнала по `(type, id)` **включая `DELETE`** (вернётся изменение с `operation=DELETE`), пусто только для неизвестного объекта; сбои применяются так же, как в `fetchChanges` 🟢.

**Сценарные ручки.** `suppressWebhooks(boolean)` — новые записи не попадают в `webhookEvents()`, но остаются в polling (`:73,103-105`; тест `:172`); `reorderNextPage()` — один раз (`:82`; тест `:228`); `failNext(times, status, retryAfter)` — следующие `times` чтений кидают `SourceUnavailableException(system, status, retryAfter)` (`:92-96,204-209`); `status`: 429 / 5xx / 0 (timeout) (Javadoc `:89`) 🟢. `webhookEvents()` — `WebhookEvent(eventId = "<code>-evt-<seq>", source = code, sourceType, sourceId, sourceVersion, operation, occurredAt = updatedAt)`, где `code` — `SourceSystem.code()` в нижнем регистре (`:192-202`) 🟢.

### Y1.3 `StubSourceServer` — HTTP-фасад

- Один `HttpServer` на `InetSocketAddress` (конструктор по умолчанию — loopback и порт 0, `SS/StubSourceServer.java:41-44`; явный адрес — для контейнера, `:46-56`). Контексты `/changes` и `/objects/` (`:53-54`). Executor не задан, обработка последовательная (поведение `HttpServer` по умолчанию) 🟡. `baseUri()` всегда `http://127.0.0.1:<port>` (`:59-61`), даже если сервер слушает `0.0.0.0` 🟡.
- `GET /changes?cursor=&limit=`: `limit` по умолчанию 100 (`:33,83`); пустой `cursor` = `null` (`:84-85`); ответ `200` с `ChangePage` в JSON (`pageJson`, `:136-145`). `GET /objects/{type}/{id}`: ровно два сегмента, иначе `404 {}`; неизвестный объект `404 {}` (`:90-100`). Формат полей — `data-dictionary.md` §Y.1.2.
- `respond` (`:102-127`) 🟢: не-`GET` → `405 {}`; затем `failIfScheduled()` (счётчик вызовов `calls` общий для обеих ручек, нумерация с 1; запланированный сбой удаляется при срабатывании, `:129-134`); `SourceUnavailableException` → HTTP-статус из исключения, **0 → 504**, `Retry-After` в секундах если задан; `IllegalArgumentException` (включая `NumberFormatException` от `limit=abc` и неизвестный курсор) → `400 {}`. Тела ошибок всегда `{}`, значения ввода не возвращаются 🟢.
- Аутентификации нет; защита — сетевая граница (`docs/03-interfaces.md:560`). Только чтение (ловушка «MCP/заглушки только читают» для источника выполнена) 🟢.

### Y1.4 Начальные данные и сценарии (`StubSources`)

- `seeded(clock)` собирает `EnumMap<SourceSystem, StubSource>` из `eam/scm/cmdb/deployMap` (`SS/StubSources.java:29-36`). Состав seed — `data-dictionary.md` §Y.1.4; согласованность идентификаторов: `SERVICE.systemCode` (SCM) = `IT_SYSTEM`-ключ EAM, `DEPLOYMENT.service` = id сервиса SCM, `hosts` = id `COMPUTE_INSTANCE` 🟢.
- Негативные сценарии — отдельные статические хелперы, **в seed они не входят** (тест `SST/StubSourceTest.java:61`): `addServiceWithBrokenReference` (SCM, `systemCode=EAM-UNKNOWN`, `:207-211`), `addDeploymentWithUnknownRepository` (`:121-130`), `addDeploymentWithSystemMismatch` (система `5102` при сервисе системы `5101`, `:138-146`), `addDeploymentWithInvalidCommit` (`commit="not-a-commit"`, `:153-161`), `addDeploymentRedeploy` (тот же `sourceId`, новый commit `1b4f0e98…`, версия `1.4.3`, `:169-178`) 🟢.
- `deploymentV1` (`:181-200`) собирает payload `deploymap-poc-1`; помимо описанного в P7.1 кладёт `name = chart` и `chartMeta.appVersion = chartVersion` 🟢. Ключ `name` в Javadoc `DeployMapFormat` не упомянут (`SS/DeployMapFormat.java:10-14`) 🟡.
- `fields(Object... kv)` — `LinkedHashMap` из пар, допускает `null`-значения; нечётное число аргументов даст `ArrayIndexOutOfBoundsException` (`:214-220`) 🟡.

### Y1.5 Заглушка EAM API

**`EamApiStore`** (`SS/EamApiStore.java`) 🟢. `byType: LinkedHashMap<type, TreeMap<id, Entry(id, ver, ctime, mtime, attrs)>>`, `synchronized`. `upsert` (`:28-37`): нового объекта — `ver=1`, `ctime=mtime=now`; существующего — **только если `attrs` изменились** (`!old.attrs().equals(attrs)`) → `ver+1`, `ctime` прежний, `mtime=now`, `attrs` заменяются **целиком**; одинаковые `attrs` — no-op (`ver`/`mtime` не меняются). `archive` (`:40-45`) физически удаляет запись: объект исчезает из списка и из `GET /{id}/`, а повторный `upsert` начнёт с `ver=1` (история версий не хранится) 🟡. `page(type, sort, page, pageSize)` (`:69-84`): по умолчанию порядок по `id`; `sort` — список через запятую, префикс `-` — убывание, допустимы `id`, `ctime`, `mtime`, хвостовое сравнение по `id` для стабильности; другой ключ → `IllegalArgumentException` (`:86-93`); страница вне диапазона — пустой список (`:82-83`).

**`EamApiStub`** (`SS/EamApiStub.java`) 🟢. Контекст `/api/` (`:65`). Порядок проверок в `route` (`:92-120`): (1) `Authorization: Token <token>` — иначе `401 {"detail":"Authentication credentials were not provided."}`; сравнение `MessageDigest.isEqual` (постоянное время, `:186-191`); (2) метод ≠ `GET` → `405`; (3) пустой путь → `404`; (4) `types` → `types(...)`; (5) тип отсутствует в хранилище (`hasType`) → `404`; (6) `/api/<type>/` → `list`; `/api/<type>/{id}/` → `find` или `404`; глубже → `404`. Ошибки разбора (`page=abc`, `id=abc`, `page<1`, `page_size<1`, неподдерживаемый `sort`, `kind` не из `fields|system`) → `400 {"detail":...}` (`:77-90`, `:122-131`, `:193-210`). Формат ответа и параметры — `data-dictionary.md` §Y.1.5. Токен обязателен и непуст (`:55-57`); он хранится как байты в приватном поле и в ответах/логах не появляется (ловушка 12) 🟢.

**Метаданные типов.** `/api/types/` — отсортированный список `typeObject` для `TYPE_IDS`; `/api/types/{id|name}/` — один тип (поиск по числовому id **или** по имени типа, `:139-141`); `/api/types/{id}/options/?kind=fields|system` — `{"options":[…]}` из `EamApiTypeMeta.options` (`SS/EamApiTypeMeta.java:25-50`). Состав ключей опций — допущение «не подтверждено» (Javadoc `:9-12`) 🟢.

**`EamApiSeed.seeded(clock)`** — 2 платформы, 2 ИТ-решения, 2 ИТ-системы; ИТ-системы несут `eam_id` (`EAM-1042`, `EAM-2001`), совпадающий с `systemCode` сервисов SCM, и `ownerTeam = TEAM-PAY` (допущение 9) (`SS/EamApiSeed.java:49-68`). Состав — `data-dictionary.md` §Y.1.4.

### Y1.6 `StubWebhookSender`, `Json`

**`StubWebhookSender`** (`SS/StubWebhookSender.java`) 🟢. `send(event)` = `sendAt(event, clock.instant())`; `sendAt` подписывает `WebhookSignature.sign(secret, ts, body)` и шлёт `POST` с заголовками `Content-Type`, `X-Archrag-Event-Id`, `X-Archrag-Timestamp`, `X-Archrag-Signature` (`:40-49,71-80`); `sendWithBadSignature` подписывает секретом `secret + "-wrong"` (`:52-56`). Тело — **только уведомление**, без payload объекта (`body`, `:59-69`). Таймауты: подключение 5 с, запрос 10 с (`:26,74`). Возвращается HTTP-статус; `IOException` → `IllegalStateException("webhook delivery failed: <класс>")` — текст исключения клиента HTTP в сообщение не попадает (секрет не утекает, ловушка 12); `InterruptedException` восстанавливает флаг и бросает `IllegalStateException` (`:81-89`) 🟢. Класс `AutoCloseable` (закрывает `HttpClient`, `:92-95`).

**`Json`** (`SS/Json.java`, package-private) 🟡. `write`: `null`, `Boolean`, `Number` (через `toString`), `Map`, `Collection`, прочее — `toString()` в строку (так сериализуются `Instant` → ISO-8601 UTC и enum → имя) (`:14,178-211`); экранируются `"`, `\`, `\n`, `\r`, `\t`, управляющие `< 0x20` как `\u00XX` (`:213-232`). `parse`: рекурсивный спуск в `Map`/`List`/`String`/`Long`/`Double`/`Boolean`/`null`; число, состоящее только из `-` и цифр → `Long`, иначе `Double` (`:164-175`); хвост после значения → `IllegalArgumentException("trailing characters")` (`:21-30`). Ограничений глубины и размера нет; неполная escape-последовательность в конце строки даёт `StringIndexOutOfBoundsException`, а не `IllegalArgumentException` (`:139-153`) — см. сомнения. Парсер используют `EamApiFormat.parse` (`SS/EamApiFormat.java:18-20`) и тесты; `write` — оба HTTP-сервера и отправитель.

### Y1.7 Тесты `platform/source-stubs` (48 `@Test`, 5 классов, 713 строк)

| Класс | `@Test` | Что покрыто |
|---|---|---|
| `SST/StubSourceTest.java` (253) | 17 | seed четырёх источников и deploy map `poc-1` (`:28,43`), негативные хелперы вне seed (`:61`), redeploy → `sourceVersion+1` (`:72`), монотонность версии (`:84`), `DELETE` виден по id и инкрементом, но не в snapshot (`:94`), стабильность snapshot при изменении посреди прохода и `snapshotComplete` только на последней странице (`:108`), инкрементальные страницы (`:138`), `PARTIAL` с `null` (`:154`), битая ссылка (`:163`), подавление webhook (`:172`), старая версия в уведомлении (`:190`), 429 с `Retry-After` и восстановление (`:204`), 503 на `fetchById` с обратным отсчётом (`:216`), `reorderNextPage` (`:228`), `limit=0` (`:243`), неизвестный объект (`:250`) |
| `SST/StubSourceServerTest.java` (121) | 8 | явный адрес (`:55`), обход курсором (`:65`), `/objects` и 404 (`:77`), `limit=0|abc` → 400 (`:85`), `failOnCall` с `Retry-After` (`:91`), сбой источника → 503 без `Retry-After` (`:101`), timeout → 504 (`:110`), `POST` → 405 (`:116`) |
| `SST/EamApiStubTest.java` (227) | 18 | голый массив трёх типов, seed `eam_id`/`id`, пагинация «короткая → пустая», сортировка `-mtime`/`-id`, `fields`, зажим `page_size`, рост `ver` и no-op при повторе, архив, 200/404/400 по id и типу, метаданные типов, ссылки `product_id`/`platform_id` — одиночные id, 401 (нет токена / неверный / `Bearer`), 405 на `POST/PATCH/DELETE`, пустой токен, разбор `ArchObject`, `fields=name,name`, `sort=name` → 400, обрыв JSON |
| `SST/StubWebhookSenderTest.java` (91) | 4 | корректная подпись → 202 и тело без состояния объекта (`:63`), устаревший timestamp → `OUT_OF_WINDOW` (`:73`), неверная подпись → `BAD_SIGNATURE` (`:79`), повтор доставки с тем же `eventId` и телом (`:85`) |
| `SST/JsonTest.java` (21) | 1 | экранирование и вложенность `write` (`:13`) |

Негативные тесты есть для лимитов (`limit`, `page_size`, `sort`), аутентификации (401), методов (405/PATCH/DELETE), подписи и окна replay; идемпотентность на стороне заглушек не проверяется (она на стороне ingestion) 🟢. Не покрыты: `Json.parse` напрямую (только через `EamApiFormat`), некорректные `s…`-курсоры, `PARTIAL` в snapshot, `sort=","`, `fields` с пробелами, `/api/types/{id}/` с несуществующим id, `EamApiTypeMeta` для `itproducts`, параллельные вызовы `StubSource` 🟡.

### Y1.8 Сомнения и расхождения

- 🔴 **`EamApiStub` уже, чем `docs/eam-api.yaml`.** `GET /api/types/{id}/options/` в спеке принимает `kind=groups|fields|computed|system` (`docs/eam-api.yaml:18075-18079`), заглушка — только `fields|system` и отвечает `400` на остальное (`SS/EamApiStub.java:43,148-153`; тест `SST/EamApiStubTest.java:142` закрепляет это как норму). Спека для списка допускает также `search`, `search_by`, `search_mode`, `enrich`, `only_names` (`docs/eam-api.yaml:15-58`) и сортировку по «полям схемы» — заглушка эти параметры молча игнорирует, а `sort` ограничивает `id|ctime|mtime` (`SS/EamApiStore.java:86-93`). Для адаптера, который начнёт использовать поиск, заглушка даст ложный «успех» 🟡. Допущения перечислены в Javadoc и `docs/06-eam-integration.md`; расхождение с ADR 0010 нет, но в документе §6 нет явного списка «чего заглушка не умеет».
- 🟡 **Docs: `docs/03-interfaces.md:534` «Нет эндпоинтов `/control/*`»** верно для `StubSourceServer`, но в `demo-launcher` `StubControl` и `EamApiControl` поднимают `POST /control/upsert` и `/control/archive` на порту 8091 (Y2). В таблице безопасности §6 (`:560-567`) они не перечислены; `docs/03-interfaces.md:3` объясняет это «описывается после E5», а compose уже в репозитории (`platform/docker/docker-compose.yml:432-441`) 🟡.
- 🟡 **`DeployMapFormat` неполно описывает poc-0.** `docs/03-interfaces.md:550` называет `ENVIRONMENT {name, class}` и `DEPLOYMENT {…, validFrom}`; это согласуется с нормализатором, который читает необязательные `class` и `validFrom` (`ingestion/normalizer/.../DeployMapMapper.java:86,241`), поэтому документ верен. Но Javadoc `DeployMapFormat` (`SS/DeployMapFormat.java:10-14`) перечисляет только `name` у `ENVIRONMENT` и `service, environment, chart, chartVersion, hosts` у `DEPLOYMENT`, а seed (`SS/StubSources.java:92-93,189-199`) `class` и `validFrom` не заполняет — необязательные поля формата заглушкой не покрыты.
- 🟡 **Два JSON-парсера.** `SS/Json` (без ограничений) дублирует `adapters/adapter-core/.../Json` (с `MAX_DEPTH = 64`, `Json.java:18`); модуль не зависит от `adapter-core` (направление зависимостей), поэтому копия объяснима. Глубокая вложенность `[[[…` даёт `StackOverflowError` в `SS/Json.value`; `\` в конце строки и обрезанная `\u` — `StringIndexOutOfBoundsException`; число `-` или `1-2` проходит проверку символов и падает как `NumberFormatException` (это `IllegalArgumentException`, но с сообщением JDK) (`SS/Json.java:135-175`). Вход — ответы собственной заглушки и fixtures, поэтому риск низкий; тест на `parse` — только через `EamApiFormat`.
- 🟡 **Необработанные исключения в HTTP-обработчиках.** Только `IllegalArgumentException` превращается в `400`. Курсор `s5` без `:` даёт `ArrayIndexOutOfBoundsException` (`SS/StubSource.java:138-140`), `sort=","` — `NullPointerException` (`"," .split` → пустой массив, `chain == null`, `SS/EamApiStore.java:73-79`), а запись `sort=id, -mtime` ошибочно отвергается `400`, потому что `trim()` применён только к ключу без `-` (`:76`). Необработанное исключение вылетает из обработчика `HttpServer`, и клиент видит обрыв соединения вместо `400` (контракт `docs/03-interfaces.md:531` обещает `400 {}` на «неизвестный курсор»).
- 🟡 **Производительность и память журнала.** Журнал неограничен; инкрементальная страница и каждая страница snapshot обходят весь журнал (`SS/StubSource.java:117-122,147-151`), snapshot из N объектов по страницам `limit` стоит O(N·N/limit). Для сценариев замеров (`docs/04-poc-measurements.md:41`, пачки upsert) на заглушке EAM это ограничивает throughput самой заглушки, а не системы — учитывать при чтении p95 🟡.
- 🟡 **Snapshot отдаёт `PARTIAL` как есть**, а `fetchById` — `DELETE`-запись; адаптеры обязаны сами различать (`completeness`/`operation`). Тест на snapshot с `PARTIAL` отсутствует.
- 🟡 **Слабые утверждения в тестах.** `pageSizeIsClampedToMaximum` проверяет только `200` при двух объектах в seed — зажим до 500 не доказан (`SST/EamApiStubTest.java:100-102`); в `suppressedWebhookIsMissingFromNotificationsButPresentInPolling` проверка `extracting(sourceId).doesNotContain("missed")` ничего не доказывает (значение `missed` — это `name`, а не `sourceId`, `SST/StubSourceTest.java:183`); `staleWebhookVersionIsLowerThanCurrentState` содержит тривиальное `isLessThan(current + 1)` (`:198-199`).
- 🟡 **`EamApiStore.archive` без истории.** Реальный EAM, скорее всего, хранит архивные объекты и отдаёт их по `GET /{id}/`; допущение «архивированный объект исчезает» зафиксировано только в Javadoc (`SS/EamApiStore.java:14-16`). После `archive`+`upsert` `ver` сбрасывается в 1 — адаптер не должен считать `ver` монотонным в пределах `id`.
- 🟢 Ловушки CLAUDE.md: заглушки только читают (`405` на не-`GET`, `SS/StubSourceServer.java:107-108`, `SS/EamApiStub.java:96-98`); время — `Instant` UTC через инжектируемый `Clock` (ловушка 10); секрет webhook и токен EAM в исключения и логи не попадают (ловушка 12, `SS/StubWebhookSender.java:83-85`, `SS/EamApiStub.java:55-57`); тексты объектов не логируются (в модуле нет логирования вообще).

### Y1.9 Итоговая строка

| Модуль | Java-файлов main / строк | Тестов | Ключевые алгоритмы | Основные риски (см. §) |
|---|---|---|---|---|
| `platform/source-stubs` | 11 (+1 `package-info`) / 1557 | 48 в 5 классах | журнал с монотонной версией; snapshot по `bound` + курсоры `s…`/`i…`; расписание сбоев по номеру вызова; `ver` по изменению `attrs`; сортировка по списку ключей | Y1.8: заглушка EAM уже спеки (`kind`, `search`, `sort`), необработанные исключения вместо 400, второй JSON-парсер, O(N·N/limit) на snapshot |

---

## Y2. `platform/demo-launcher`

**Назначение.** Исполняемый Spring Boot-fat-jar (`classifier=exec`, `mainClass=DemoLauncher`, `platform/demo-launcher/pom.xml:72-89`) — один образ стенда для **четырёх ролей**: `adapter <SRC>` (адаптер источника с webhook, polling, reconciliation и `/health`), `stub <SRC>` (заглушка источника + управляющий вход), `eam-api-stub` (заглушка формата реального EAM + управляющий вход) (`DL/DemoLauncher.java:26-48,58-83`) 🟢. Это не Spring-приложение: контекста Spring нет, используются `main`, JDK `HttpServer` и env (`DL/DemoLauncher.java:3,58`) 🟢. В compose все роли — один `Dockerfile` (`platform/docker/Dockerfile:1-14`), роль выбирается `command:` сервиса (`platform/docker/docker-compose.yml:313,340,354,368,383,399,410,421,436`), секреты Docker превращает в env `platform/docker/entrypoint.sh:4-10` 🟢.

### Y2.1 Состав

| Файл | Строк | Что внутри |
|---|---|---|
| `DL/DemoLauncher.java` | 168 | `main`, три режима запуска, сборка адаптера, `/health`, ожидание SIGTERM |
| `DL/LauncherConfig.java` | 205 | разбор env в записи `Adapter`, `Stub`, `EamApiStub`; валидация портов/интервалов |
| `DL/StubControl.java` | 125 | `POST /control/upsert` для заглушки источника (+ необязательный webhook адаптеру) |
| `DL/EamApiControl.java` | 112 | `POST /control/upsert`, `/control/archive` для заглушки EAM API |
| `DL/package-info.java` | 6 | Javadoc пакета |

Всего 5 main-файлов, 616 строк. Тесты: 3 класса, 28 `@Test`, 424 строки (Y2.6). Зависимости: `source-spi`, `adapter-core`, четыре адаптера, `event-schemas`, `event-journal`, `source-stubs`, `postgresql` (`platform/demo-launcher/pom.xml:17-57`) — платформенный «сборочный» модуль, направление зависимостей схемы `platform → всё` не нарушает (CLAUDE.md, раскладка модулей) 🟢.

### Y2.2 Жизненный цикл `DemoLauncher`

**Диспетчер** (`DL/DemoLauncher.java:58-83`) 🟢:
1. `args == ["eam-api-stub"]` → `runEamApiStub(LauncherConfig.eamApiStub(System.getenv()))` (`:59-67`).
2. Иначе нужны ровно два аргумента `adapter|stub` + код источника; иначе `usage` в stderr и `System.exit(2)` (`:68-71`).
3. `LauncherConfig.parseSystem(args[1])` и конфиг роли из `System.getenv()`; любой `IllegalArgumentException` → `configuration error: <сообщение>` в stderr и `exit(2)` (`:72-82`). Сообщения содержат только **имена** переменных (ловушка 12; тесты `DLT/LauncherConfigTest.java:49,77`).

**`runStub`** (`:85-95`): `StubSources.seeded(Clock.systemUTC()).get(system)` — **seed всех четырёх, берётся один**; `StubWebhookSender` создаётся только если задан `ARCHRAG_WEBHOOK_TARGET`; `StubSourceServer` слушает `0.0.0.0:ARCHRAG_STUB_PORT`, `StubControl` — `0.0.0.0:8091` (константа `STUB_CONTROL_PORT`, `:54`). Лог `stub <SRC> listens on …` и `awaitShutdown()`. `StubWebhookSender` в `try-with-resources` не включён и не закрывается 🟡.

**`runEamApiStub`** (`:97-106`): `EamApiSeed.seeded` → `EamApiStub` на `ARCHRAG_STUB_PORT` с токеном `ARCHRAG_EAM_API_TOKEN`, `EamApiControl` на 8091. `IOException` при старте управляющего входа превращается в `IllegalStateException` (не в код 2).

**`runAdapter`** (`:108-135`) — порядок старта 🟢:
1. `PGSimpleDataSource` из `ARCHRAG_PG_*` → `PostgresEventJournal(ds)`; миграции **не накатываются** (их делает `ingestion-service`, поэтому в compose `adapter-*` зависят от `ingestion`, `platform/docker/docker-compose.yml:28-32`).
2. `AdapterConfig(system, sourceUrl, webhookSecret, DEFAULT_REPLAY_WINDOW (5 мин), pageSize=100, pollInterval, reconcileInterval, RetryPolicy.defaults())` — размер страницы 100 зашит (`:114-116`; `adapters/adapter-core/.../AdapterConfig.java:21-29,61`).
3. `S3RawPayloadStore.create(endpoint, access, secret, bucket)` (в `try-with-resources`) → `store.ensureBucket()`.
4. `create(...)` (`:137-145`): `EAM` → `EamAdapter.create(config, journal, store)` либо, если задан `config.eamApi()`, `EamAdapter.create(config, eamApi, journal, store)`; `SCM`/`CMDB`/`DEPLOY_MAP` → соответствующие `…Adapter.create`.
5. `healthServer(HEALTH_PORT, started)` создаётся **до** запуска адаптера: пока `started=false`, `GET /health` → `503 {"status":"DOWN"}` (также на не-`GET`), после — `200 {"status":"UP"}` (`:147-160`).
6. `startWebhook(0.0.0.0:WEBHOOK_PORT)` → `startPolling()` → `startReconciliation()` → `started.set(true)`; `finally` останавливает health-сервер (`:123-131`).
7. `awaitShutdown()` (`:163-167`): `CountDownLatch` и shutdown hook, который лишь снимает защёлку; ресурсы закрывает `try-with-resources` после возврата из `await` — но JVM не ждёт главный поток после хука, поэтому фактическое закрытие при SIGTERM не гарантировано (Javadoc `:162` так и говорит: «ресурсы закрывает ОС») 🟡. Корректность не страдает: checkpoint сдвигается адаптером после коммита (ловушка 6), повтор безопасен.

Инвариант из Javadoc: **ровно один экземпляр адаптера на источник**, замок snapshot действует только внутри процесса (`:46`; в compose `replicas: 1`, `platform/docker/README.md:30`) 🟢.

### Y2.3 `LauncherConfig` — разбор env

Список переменных, умолчания и коды — `data-dictionary.md` §Y.2.1. Алгоритм 🟢:
- `adapter(system, env)`: обязательные 9 переменных (`PG_URL`, `PG_USER`, `PG_PASSWORD`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET`, `SOURCE_URL`, `WEBHOOK_SECRET`) собираются в список `missing`; пустая/пробельная строка = не задано; если список непуст — одно `IllegalArgumentException("required environment variables are not set: [..]")` с **именами** (`DL/LauncherConfig.java:107-121,165-173`). Для `EAM` дополнительно `eamApi(env, missing)` (`:118,128-139`). Необязательные: `WEBHOOK_PORT` (8080), `HEALTH_PORT` (8090), `POLL_INTERVAL_SECONDS` (30), `RECONCILE_INTERVAL_SECONDS` (21600) (`:122-124`).
- `eamApi`: `ARCHRAG_EAM_MODE` пусто/`legacy` → `null`; `eam-api` → требуются `EAM_API_URL` и `EAM_API_TOKEN` (имена добавляются в `missing`); иное значение → сразу `IllegalArgumentException` (`:128-139`). Для не-`EAM` источников режим игнорируется (тест `DLT/LauncherConfigTest.java:153`).
- `stub(system, env)`: без `WEBHOOK_TARGET` → `Stub(system, port, null, null)` — `WEBHOOK_SECRET` в этом случае **игнорируется**; с целью — секрет обязателен (`:142-153`).
- `eamApiStub(env)`: обязателен `EAM_API_TOKEN` (`:156-163`).
- `port` — целое `1..65535` (`:174-188`); `seconds` — целое `≥ 1` (`:190-203`); сообщения называют переменную, но не значение.
- Записи `Adapter`, `Stub`, `EamApiStub` переопределяют `toString` с `secrets=***`/`token=***`; `Adapter.toString` печатает `eamApi` через собственный маскирующий `EamAdapter.EamApi.toString` (`:66-70,82-84,91-93`; `adapters/eam-adapter/.../EamAdapter.java:47-53`) 🟢 (ловушка 12).

### Y2.4 `StubControl` и `EamApiControl`

Оба — отдельный `HttpServer` на порту 8091, **без аутентификации**, на всех интерфейсах контейнера (`new InetSocketAddress(STUB_CONTROL_PORT)`, константа `8091`: `DL/DemoLauncher.java:54, 90, 100`); защита — что порт не публикуется и не проксируется (Javadoc `DL/StubControl.java:18-20`, `DL/EamApiControl.java:19-21`, `platform/docker/README.md:51`) 🟡. Исполняется только изнутри контейнера (`docker compose exec … curl`), этим пользуются `platform/docker/scripts/backup-restore-check.sh:16`, `deployment-commit-check.sh:107`, `measure.sh:196,260`.

**`StubControl`** (`DL/StubControl.java`) 🟢: `POST /control/upsert` с телом `{"type","id","payload","notify"?}`.
1. Не-`POST` → `405 {"error":"POST expected"}` (`:56-58`).
2. Тело читается не более `MAX_BODY_BYTES = 64 KiB`: `readNBytes(64K+1)`, больше → `IllegalArgumentException` → `400` (`:113-119`); разбор — `adapter-core.Json.parseObject` (с лимитом глубины 64, `adapters/adapter-core/.../Json.java:18,40-45`).
3. `type`, `id` — непустые строки, `payload` — JSON-объект (`:61-66,106-111`); иначе `400 {"error":"invalid request"}` **без эха ввода** (`:77-80`; ловушка 11).
4. `source.upsert(type, id, payload)` (всегда `COMPLETE`); `notify` по умолчанию `true`, отключается только литералом `false` (`:69-70`).
5. Если задан `StubWebhookSender` и `notify`: `notifyAdapter` берёт **последнее** событие `(type, id)` из `webhookEvents()` и шлёт `webhooks.send(event)`; при `IllegalStateException` (доставка не удалась) — лог `WARNING` и статус `0`; при успехе — HTTP-статус адаптера (`:89-104`). Ответ `200 {"sourceVersion":N[,"webhookStatus":S]}`; поле `webhookStatus` отсутствует, если webhook не отправлялся (`:72-76`). Сбой доставки upsert не откатывает: догонит polling.
Отправка синхронна внутри обработчика (до 10 с таймаута отправителя), обработка на `HttpServer` последовательная 🟡.

**`EamApiControl`** (`DL/EamApiControl.java`) 🟢: контексты `/control/upsert` (`{"type","id","attrs"}`) и `/control/archive` (`{"type","id"}`) (`:33-34`). Общая обёртка `handle` (`:77-98`): не-`POST` → `405`; `IllegalArgumentException` → `400 {"error":"invalid request"}`. `type` ∈ `{platforms, itproducts, itsystems}` (`:27,59-64`); `id` — только JSON-целое (`Long`), строка `"x"` → `400` (`:66-71`); `attrs` — объект (`:45-47`). `upsert` **заменяет `attrs` целиком** и возвращает `{"ver":N}` (`:42-52`); `archive` идемпотентен — для неизвестного объекта тоже `200 {}` (`:54-57`). `/api/**` заглушки остаётся только для чтения (тест `DLT/EamApiControlTest.java:77`).

### Y2.5 Блок `/health` и порты

Порты роли: адаптер — `WEBHOOK_PORT` (8080; `/webhook` и `/control/snapshot` от `WebhookServer`), `HEALTH_PORT` (8090); заглушка — `STUB_PORT` (8080) и 8091; `eam-api-stub` — `STUB_PORT` (8080) и 8091 (`DL/DemoLauncher.java:54,89-90,99-100,122-124`; `LauncherConfig.java:122-124`). Health заглушек в compose — `GET /changes?limit=1` на 8080, `eam-api-stub` — `GET /api/platforms/?page_size=1` с токеном (`platform/docker/docker-compose.yml:390-395,441`) 🟢.

### Y2.6 Тесты `platform/demo-launcher` (28 `@Test`, 3 класса, 424 строки; `*IT` нет)

| Класс | `@Test` | Что покрыто |
|---|---|---|
| `DLT/LauncherConfigTest.java` (159) | 14 | умолчания и переопределения адаптера (`:29,39`), ошибка с перечислением имён и без значений секретов (`:49`), неверный порт/интервал/не число (`:61`), `toString` без секретов (`:77`), неизвестный источник/`null` (`:83`), заглушка без секретов и порт (`:90`), токен EAM API обязателен и скрыт (`:96`), цель webhook требует секрет (`:107`) и разбор (`:114`), без цели секрет игнорируется (`:123`), режим `legacy` по умолчанию (`:129`), `eam-api` требует URL и токен (`:137`), неизвестный режим и игнор для не-EAM (`:153`) |
| `DLT/StubControlTest.java` (184) | 10 | upsert виден в `/changes` (`:56`), плохие тела → 400 без изменения источника (`:64`), тело > 64 KiB → 400 (`:74`), `GET` → 405 (`:80`), control не обслуживается на порту источника (`:87`), подписанный webhook с проверкой подписи и `eventId` (`:123`), `notify:false` (`:141`), недоступный получатель → 200 и `webhookStatus:0` (`:156`), ошибка получателя 401 отражается, а не бросается (`:169`), без цели нет `webhookStatus` (`:181`) |
| `DLT/EamApiControlTest.java` (81) | 4 | upsert/рост `ver` и видимость в `/api` (`:48`), архивирование (`:58`), плохие запросы без эха ввода: не JSON, чужой тип, нецелый id, нет `attrs`, `GET` → 405 (`:65`), `/api/**` остаётся read-only (`:77`) |

Негативные тесты есть для валидации env, размера и формата тела, методов; не покрыты: `DemoLauncher.main` (маршрутизация аргументов, коды выхода), `runAdapter` и `/health`, `create(...)`, закрытие `StubWebhookSender`, `ARCHRAG_STUB_PORT` вне диапазона для `stub`, `archive` неизвестного объекта и неверный `type` у `/control/archive`, подпись webhook при `notify` для `PARTIAL`-записей (control всегда шлёт `COMPLETE`) 🟡. Запуск процесса и `/health` проверяет только смоук стенда (`platform/docker/smoke.sh`) — вне этих модулей.

### Y2.7 Сомнения и расхождения

- 🟡 **Управляющий вход без аутентификации на wildcard-адресе.** `POST /control/upsert|archive` меняет данные источника, слушает `0.0.0.0:8091` в сети `edge`, где находятся адаптеры (`platform/docker/docker-compose.yml:381-388`); единственная защита — «не публикуется» (комментарий `:432`). Это согласуется с общей установкой `docs/03-interfaces.md:560` («защита — сетевая граница») для `/control/snapshot` и `/changes`, но сами `/control/*` заглушек в таблице §6 не перечислены (см. Y1.8). Для демо-стенда допустимо; для любого другого развёртывания — риск.
- 🔴 **`EamApiControl.TYPES` продублирован литералами** (`DL/EamApiControl.java:27`), тогда как `EamApiSeed` объявляет `SOLUTION_TYPE` «единственным местом выбора» типа ИТ-решения (Javadoc `SS/EamApiSeed.java:23-26`). Смена константы переключит заглушку на новый тип, а управляющий вход продолжит принимать только `itproducts` и отвергать новый тип (`400`). Тест `DLT/EamApiControlTest` этого не ловит.
- 🟡 **Ошибки времени выполнения маскируются под конфигурацию.** `catch (IllegalArgumentException)` вокруг всего `runAdapter(...)`/`runStub(...)` (`DL/DemoLauncher.java:72-82`) печатает `configuration error` и код 2 для любого `IllegalArgumentException`, включая возникшее при старте адаптера/сервера (например, из `HttpServer.create` для порта вне диапазона). Сообщение может содержать текст исключения библиотеки.
- 🟡 **`URI.create(...)` для `S3_ENDPOINT`, `SOURCE_URL`, `EAM_API_URL`, `WEBHOOK_TARGET`** (`DL/LauncherConfig.java:122-123,138,152`) при неверном URI кидает `IllegalArgumentException`, текст которого содержит **значение** (JDK добавляет входную строку). Если в URL окажутся учётные данные (`user:pass@host`), они попадут в stderr, вопреки заявленному «значения в сообщения не попадают» (Javadoc `DL/LauncherConfig.java:11-12`, ловушка 12). Тест этого не проверяет.
- 🟡 **`StubWebhookSender` не закрывается** в `runStub` (`DL/DemoLauncher.java:87-94`) — `HttpClient` живёт до конца процесса; на практике безвреден (процесс завершается).
- 🟡 **Корректное завершение не гарантировано** (Y2.2, п. 7): `awaitShutdown` не ждёт закрытия адаптера, in-flight обработка может прерваться; безопасность данных держится на идемпотентности ingestion, не на lifecycle.
- 🟡 **`DemoLauncher` без unit-тестов**: маршрутизация аргументов, `exit(2)`, порядок старта, `/health` до/после старта — не покрыты; всё опирается на смоук `platform/docker/smoke.sh`.
- 🟡 **Hard-coded значения.** Порт 8091 (`DL/DemoLauncher.java:54`) и `pageSize=100` (`:115`) не настраиваются через env; `ARCHRAG_STUB_PORT` служит и портом `stub`, и портом `eam-api-stub`. Для `stub` на одном хосте с `eam-api-stub` (без compose-изоляции сетей) порт 8091 конфликтует.
- 🟡 **Версия плагина Spring Boot** задана свойством `${spring-boot.version}` прямо в `build/plugins` (`platform/demo-launcher/pom.xml:72-76`), а не через `pluginManagement` корневого `pom.xml:176-208` (там плагина нет). Так же сделано в `ingestion-service`, `mcp-server`, `admin-console` (соседний код), поэтому расхождение со стилем нулевое; версия при этом всё же определяется в одном месте (`pom.xml:28`). Решение (вынести в `pluginManagement`) — за архитектором.
- 🟢 Ловушки CLAUDE.md: секреты (PG/S3/webhook/EAM-токен) не логируются и не попадают в `toString` (тесты `DLT/LauncherConfigTest.java:77,96,114,137`); тело control — недоверенный ввод: ограничение размера, `400` без эха (`DLT/StubControlTest.java:64,74`, `DLT/EamApiControlTest.java:65`); `catch` не глушит ошибки — `IllegalStateException` доставки логируется как `WARNING` и отражается статусом `0` (`DL/StubControl.java:100-103`).

### Y2.8 Итоговая строка

| Модуль | Java-файлов main / строк | Тестов | Ключевые алгоритмы | Основные риски (см. §) |
|---|---|---|---|---|
| `platform/demo-launcher` | 4 (+1 `package-info`) / 616 | 28 в 3 классах (`main`/`runAdapter` без тестов) | разбор env с накоплением `missing`; порядок старта адаптера (миграции — не здесь) и `/health` после старта; control-вход с лимитом 64 KiB и необязательным webhook | Y2.7: `/control/*` без аутентификации на wildcard, `TYPES` продублирован, `configuration error` для любого IAE, `URI.create` может эхо-вывести URL |


---

## Z1. `platform/integration-tests`

> Archaeologist, этап R.2e (`platform/integration-tests`). Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Тесты в этом прогоне **не запускались** (только чтение; Docker не использовался), поэтому утверждения о поведении — это то, что тест *утверждает*, а не результат прогона.
> Сокращение: `IT` = `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests`. Ссылка `Класс.метод:N` — строка объявления метода (`void …`) в `IT/Класс.java`; ссылка `Класс:N-M` — строки файла. Номера строк методов получены машинной выгрузкой по `@Test`.
> Не дублируется: требования и критерии PoC — `docs/00-vision.md:794-807`; порядок работ F1–F4 — `docs/01-development-order.md:36-45`; решения — `docs/adr/` (0007, 0017, 0019, 0020, 0030, 0031).

**Назначение.** Единственный модуль сквозной приёмки: поднимает настоящие Neo4j Community, PostgreSQL и S3-совместимое хранилище в Testcontainers и прогоняет через них код всех остальных групп (`adapters/*` → `ingestion/*` → `access/*`) на заглушках `platform/source-stubs`. Модуль не содержит прод-логики: в `src/main` лишь маркер (`platform/integration-tests/src/main/java/io/github/unlocker/archrag/integrationtests/IntegrationTestsModule.java:3-6`, `package-info.java`), а все 31 класс `*IT`, `IntegrationTestsSmokeTest` и 6 вспомогательных классов лежат в `src/test` 🟢.

---

### Z1.1 Состав и сборка

**Состав** 🟢 (машинный подсчёт): 38 файлов в `src/test` — **31 класс `*IT`** (298 методов `@Test`), 1 unit-класс `IntegrationTestsSmokeTest` (1 `@Test`, запускается surefire) и 6 вспомогательных (`AssetFixtures`, `CanonicalGraph`, `EamApiSupport`, `McpTestJwt`, `ScmItSystemMapper`, `TestImages`). Итого **299 `@Test`**, `@ParameterizedTest`/`@RepeatedTest`/`@TestFactory` — 0, `@Disabled` — 0, `@DisplayName` — 0. ≈ 10 880 строк в `src/test` (классы `*IT` ≈ 10 520). Совпадает с `_reversa_sdd/inventory.md:16` (31 `*IT.java`).

| Класс | Область | Строк | @Test | Ст. | Кон. | JWT | @Order |
|---|---|---|---|---|---|---|---|
| `AdapterInboxIT` | ингест/журнал | 151 | 3 | M | PS | — | — |
| `AdminConsoleIT` | консоль | 334 | 8 | SA | N | RSA | — |
| `AdminEndpointIT` | admin REST | 477 | 13 | SB | NPS | RSA | — |
| `ContainersSmokeIT` | среда | 87 | 3 | — | NPS | — | — |
| `DeployedFromIT` | deploy map / конфликты | 371 | 11 | M | NP | — | — |
| `DeploymentCommitReadIT` | query-core | 173 | 4 | M | N | — | — |
| `EamApiProjectionIT` | проекция EAM | 271 | 9 | M | NP | — | — |
| `EamKeyMigrationIT` | миграция ключа EAM | 324 | 5 | SB | NPS | HS | 5 |
| `EventJournalIT` | ингест/журнал | 300 | 12 | M | PS | — | — |
| `ExplainProvenanceIT` | query-core / provenance | 274 | 5 | M | NP | — | — |
| `F1AcceptanceIT` | приёмка F1 | 428 | 8 | SB+SA | NPS | RSA(MCP)+HS | 8 |
| `F2AcceptanceIT` | приёмка F2 | 521 | 9 | SB+SA | NPS | RSA(MCP)+HS | 9 |
| `F3AcceptanceIT` | приёмка F3 | 539 | 8 | SB+SA | NPS | RSA(MCP)+HS | 8 |
| `F4AcceptanceIT` | приёмка F4 | 544 | 7 | SB+SA | NPS | RSA(MCP)+HS | 7 |
| `GetAssetIT` | query-core | 175 | 6 | M | N | — | — |
| `GraphProjectorIT` | projector | 1109 | 51 | M | NP | — | — |
| `GraphQueryCoreIT` | query-core | 229 | 11 | M | N | — | — |
| `IdentityCandidateIT` | identity | 275 | 8 | M | NP | — | — |
| `IdentityMappingIT` | identity | 192 | 9 | M | P | — | — |
| `IngestionMetricsIT` | метрики | 94 | 1 | M | P | — | — |
| `IntegrationTestsSmokeTest` | smoke (surefire) | 13 | 1 | — | — | — | — |
| `JournalDispatcherIT` | ингест/журнал | 297 | 6 | SB | NPS | HS | — |
| `Neo4jSchemaIT` | схема/миграции | 90 | 4 | M | N | — | — |
| `PlatformInvariantIT` | инварианты платформы | 249 | 7 | M | NP | — | — |
| `PostgresSourceConflictsIT` | конфликты (PG) | 273 | 12 | M | P | — | — |
| `ReconciliationIT` | sync / reconcile | 400 | 6 | M | NP | — | — |
| `RuntimeFootprintIT` | query-core | 277 | 9 | M | N | — | — |
| `SearchAssetsIT` | query-core | 255 | 13 | M | N | — | — |
| `SourceConflictIT` | конфликты (граф) | 345 | 7 | M | NP | — | — |
| `SyncAcceptanceIT` | sync | 543 | 11 | SB | NPS | HS | 11 |
| `SyncConsoleIT` | консоль | 376 | 14 | SA | NP | RSA | — |
| `TraceDependenciesIT` | query-core | 393 | 18 | M | N | — | — |

Колонки: **Ст.** — стиль запуска сервисов: `M` — собираются руками (`new …`, без Spring); `SB` — `@SpringBootTest(IngestionServiceApplication, RANDOM_PORT)`; `SA` — отдельное приложение через `new SpringApplicationBuilder(...).run(args)`; `SB+SA` — ingestion как `@SpringBootTest` плюс второе приложение (MCP) в том же тесте. **Кон.** — контейнеры: `N` Neo4j, `P` PostgreSQL, `S` S3. **JWT**: `RSA` — `McpTestJwt` (настоящая проверка подписи по JWKS), `HS` — подменённый `JwtDecoder` на HS256, `—` токенов нет. **@Order** — число методов с `@Order`.

**Maven** 🟢.
- `platform/integration-tests/pom.xml:19-123`: все зависимости со `scope=test` (версий нет — BOM корня): 17 проектных модулей (`canonical-model`, `event-schemas`, `source-spi`, `adapter-core`, `source-stubs`, 4 адаптера, `event-journal`, `normalizer`, `identity-resolution`, `graph-projector`, `ingestion-service`, `graph-query-core`, `mcp-server`, `admin-console`), `spring-boot-starter-test`, `testcontainers-{junit-jupiter,neo4j,postgresql}`, `neo4j-java-driver`, `postgresql`. `admin-console` стоит после `mcp-server` намеренно (`pom.xml:92`): на classpath два `application.yml`, порядок сохраняет прежнюю загрузку 🟡 (`F1AcceptanceIT` обходит неопределённость, передавая все свойства MCP аргументами, `F1AcceptanceIT:186-190`).
- `nimbus-jose-jwt`, клиент MCP (`io.modelcontextprotocol.client`), `spring-security-oauth2-jose`, logback, AWS SDK в `pom.xml` не объявлены: приходят транзитивно через `mcp-server`/`ingestion-service`/`event-journal` (`McpTestJwt:3-8`, `F1AcceptanceIT:20-23`, `SyncConsoleIT` использует `ch.qos.logback.classic`) 🟡. Исчезновение транзитивной зависимости сломает компиляцию тестов, а не прод.
- **failsafe только под профилем `integration-tests`** (`pom.xml:136-157`, комментарий `:136`): обычный `./mvnw verify` не запускает ни один `*IT` и не требует Docker; surefire по умолчанию берёт `*Test` и запускает единственный `IntegrationTestsSmokeTest:9-12`. Плагины и версии (3.5.6) заданы в корне (`pom.xml:34-35,189-196`); конфигурации (параллелизм, `forkCount`, `reuseForks`, таймаут форка, `includes`) нет — действуют умолчания: один форк, классы последовательно, `*IT` подбирается по имени 🟡.
- **CI**: job `integration-tests` — `./mvnw -B -ntp verify -Pintegration-tests`, без `needs` (параллельно с `build`), при падении выгружает `target/surefire-reports` и `target/failsafe-reports` (`.github/workflows/ci.yml:35-55`); README: «требует запущенный Docker» (`README.md:44`) 🟢.

---

### Z1.2 Инфраструктура

#### Z1.2.1 Контейнеры и образы 🟢

| Сервис | Образ (точный тег) | Где задан | Классов используют |
|---|---|---|---|
| Neo4j Community | `neo4j:5.26.31-community` (5.26 LTS) | `TestImages:9` (`TestImages.NEO4J`) | 26 |
| PostgreSQL | `postgres:16` — **мажорный тег, не точный** 🟡 | литерал в каждом классе (`ContainersSmokeIT:41`; `new PostgreSQLContainer("postgres:16")` в 23 классах) | 23 |
| S3 | `chrislusf/seaweedfs:4.48` (SeaweedFS, не MinIO) | `ContainersSmokeIT:35` (`S3_IMAGE`), фабрика `ContainersSmokeIT:52-60` | 11 (через `ContainersSmokeIT.s3Container()`) |

- Контейнер S3: команда `server -s3 -dir=/data`, ключи `test-access-key`/`test-secret-key` из env `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (`ContainersSmokeIT:44-46,54-56`), порт 8333, ожидание `GET /` с кодом < 500 и `withStartupTimeout(120 s)` (`ContainersSmokeIT:57-59`). Это единственная настроенная таймаутом стратегия ожидания; Neo4j и PostgreSQL ждут умолчаниями Testcontainers 2.0.5 (`pom.xml:30`).
- **Полная пересборка на класс.** Контейнеры `static final` и принадлежат классу; `withReuse` не вызывается нигде, фиксированных портов нет (`getMappedPort`/`getBoltUrl`). За прогон ≈ **60 запусков контейнеров** (26 Neo4j + 23 PostgreSQL + 11 S3) 🟢. Общего singleton-контейнера нет, «сбрасывается» граф внутри класса, а не между классами 🟡.
- **Расхождение с текстом `CLAUDE.md`.** `CLAUDE.md` называет MinIO для S3 в тестах; в репозитории SeaweedFS по принятому решению `docs/adr/0007-s3-raw-storage-seaweedfs.md:1,10` («MinIO больше не публикует образы»). Код прав, текст агентского профиля устарел 🟡. Точный тег требуется `CLAUDE.md` для Neo4j — соблюдён; для PostgreSQL точного тега не требуется, но `postgres:16` плавающий в пределах мажора 🟡.

#### Z1.2.2 Четыре способа запуска тестируемого кода 🟢

| Способ | Классы | Как |
|---|---|---|
| **M — вручную, без Spring** | 19 классов (журнал, identity, projector, query-core, ряд проекций) | `@Testcontainers` + `@Container static final`; в `@BeforeAll` `new GraphDatabase.driver`, `Neo4jSchema.apply`, `PGSimpleDataSource`, `JournalMigrations.apply`, затем `new PostgresEventJournal / PostgresIdentityMapping / GraphProjector / EventProcessor(...)` (`GraphProjectorIT:89-110`). Без HTTP и без JWT |
| **SB — `@SpringBootTest` ingestion** | `AdminEndpointIT`, `JournalDispatcherIT`, `EamKeyMigrationIT`, `SyncAcceptanceIT`, `F1–F4` | `@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = RANDOM_PORT)`; свойства — `@DynamicPropertySource` (datasource, `archrag.neo4j.*`, `archrag.s3.*`, `jwt.*`, `archrag.dispatcher.*`, `archrag.adapters.*.control-url`), например `AdminEndpointIT:84-105` |
| **SA — второе приложение вручную** | `AdminConsoleIT` (2 контекста), `SyncConsoleIT`, MCP внутри `F1–F4` | `new SpringApplicationBuilder(X.class).run(args)`; свойства — **аргументами командной строки** (`--server.port=0`, `--spring.autoconfigure.exclude=DataSource…,Flyway…`), потому что на classpath два `application.yml` (`F1AcceptanceIT:186-222`, `AdminConsoleIT:130-155`, `SyncConsoleIT:128-151`) |
| **Один бин как Java-метод** | `ExplainProvenanceIT` | `AnnotationConfigApplicationContext` с `ExplainProvenanceTool` и `GraphQueries`; без Security, `ToolScopeFilter`, HTTP (`ExplainProvenanceIT:125-130`) |

Жизненный цикл контейнеров у классов `SB` двух видов 🟢: (а) `@Testcontainers` + `@Container` — `AdminEndpointIT:54,64-71`, `JournalDispatcherIT:60,68-75`; (б) `@TestInstance(PER_CLASS)` + `static { NEO4J.start(); POSTGRES.start(); S3.start(); }` без `@Testcontainers` — `F1–F4`, `EamKeyMigrationIT:90-94`, `SyncAcceptanceIT:107-111`. Причина (б) — в комментарии (`F1AcceptanceIT:93`, `EamKeyMigrationIT:89`): при `PER_CLASS` контекст Spring создаётся раньше расширения Testcontainers, и `@DynamicPropertySource` не получил бы адресов. Явного `stop()` нет — гасит Ryuk 🟡.

#### Z1.2.3 Как применяется схема 🟢

Тремя путями — **тем же кодом, что и в проде**, отдельной тестовой DDL нет:
1. *Напрямую* (классы `M`): `Neo4jSchema.apply(driver)` и `JournalMigrations.apply(dataSource)` (`GraphProjectorIT:91,97`; у `Neo4jSchemaIT` — дважды, как проверка идемпотентности, `Neo4jSchemaIT:33-34`).
2. *Через приложение* (классы `SB`): `IngestionServiceConfiguration` вызывает `JournalMigrations.apply(dataSource)` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:47`) и в бине `neo4jDriver` — `Neo4jSchema.apply` + `TemporalRelationMigration.migrate(now)` (`:68-72`). Flyway Boot не подключён (`:38`). Тесты схему явно не вызывают 🟡 (выведено из отсутствия вызова).
3. *Никак*: `GraphQueryCoreIT` и `RuntimeFootprintIT` работают на «голой» базе без constraint/индексов (только `CREATE USER mcp-reader`), `AdminConsoleIT` и остальные чтения применяют `Neo4jSchema.apply` 🟡 — стоимость EXPLAIN/запросов на пустой схеме отличается от прода.

`JournalMigrations.apply` = `Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()` (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java:12-13`). На classpath IT это **V1–V7 из трёх модулей** (`V1` event-journal; `V2, V4, V5, V7` identity-resolution; `V3, V6` ingestion-service), поэтому классы `M` видят и роль `archrag_console_ro` (V6), и `source_conflict.kind` (V7): ни один модуль в одиночку всех семи не видит 🟢. Следствие 🟡: набор таблиц в тесте зависит от состава зависимостей `pom.xml` модуля.

#### Z1.2.4 Изоляция и состояние 🟢

| Стратегия | Классы |
|---|---|
| `@BeforeEach MATCH (n) DETACH DELETE n` (только Neo4j) | `GraphProjectorIT:113-117`, `GraphQueryCoreIT`, `GetAssetIT`, `SearchAssetsIT`, `TraceDependenciesIT`, `DeploymentCommitReadIT`, `RuntimeFootprintIT`, `EamApiProjectionIT:88-91`, `PlatformInvariantIT:91-97` (+ `DELETE FROM source_conflict`), `DeployedFromIT:92-98` (+ `source_conflict`) |
| `@BeforeEach` чистит и Neo4j, и PG (`TRUNCATE dlq_entry, inbox_event, consumer_checkpoint`) | `ReconciliationIT:108-121` |
| Только `TRUNCATE consumer_checkpoint` | `JournalDispatcherIT:113-119` (граф и журнал не чистятся) |
| **Нет очистки**, изоляция уникальными `UUID`/`uid()` в ключах | `EventJournalIT`, `IdentityMappingIT`, `IdentityCandidateIT`, `PostgresSourceConflictsIT`, `SourceConflictIT`, `ExplainProvenanceIT`, `AdapterInboxIT`, `AdminConsoleIT`, `SyncConsoleIT` |
| **Нет очистки, состояние копится намеренно** (последовательный сценарий) | `F1–F4`, `SyncAcceptanceIT`, `EamKeyMigrationIT`, `AdminEndpointIT` (ленивый `seed()` и мутирующие тесты) |

Время: фиксированные константы `Instant.parse("2026-…Z")` почти везде; реальные часы (`Clock.systemUTC()`) — в классах с настоящими адаптерами и `Reconciler` (`ReconciliationIT`, `SourceConflictIT:140`, `AdminEndpointIT:138`, `SyncAcceptanceIT:180`, `F1–F4`) 🟢. Подмены часов нет.

#### Z1.2.5 Ожидания, таймауты, `Thread.sleep` 🟢

Awaitility в проект не входит (комментарий «новую зависимость в задаче не вводим»: `F1AcceptanceIT:413`, `EamKeyMigrationIT:309`), поэтому в каждом классе свой цикл `awaitTrue`:

| Где | Шаг | Таймаут | Строка |
|---|---|---|---|
| `F1–F4`, `EamKeyMigrationIT` | `Thread.sleep(200)` | 90 с (`TIMEOUT`) | `F1AcceptanceIT:84,421`; `F2AcceptanceIT:88,514`; `F3AcceptanceIT:87,532`; `F4AcceptanceIT:106,537`; `EamKeyMigrationIT:311,317` |
| `SyncAcceptanceIT` | `Thread.sleep(200)`; плюс пауза «ничего не должно измениться» `Thread.sleep(2000)` (= `2 × DISPATCHER_POLL`) | 60 с по умолчанию | `SyncAcceptanceIT:526,536,329` |
| `JournalDispatcherIT` | `Thread.sleep(100)` | 30 с | `JournalDispatcherIT:284,290` |
| MCP-клиент (`F1–F4`) | `requestTimeout` | 60 с | `F1AcceptanceIT:367` |
| прогрев Neo4j | `QueryLimits.timeout` | 120 с (`DeploymentCommitReadIT:63`) | — |
| индексы Neo4j | `CALL db.awaitIndexes(30)` | 30 с (серверное ожидание, не sleep) | `SearchAssetsIT:118-120`, `AdminConsoleIT:121` |

Бюджет запроса в IT: `QueryLimits(6, 500, 50, 5 s, 512 KiB)`; таймаут берётся из системного свойства `archrag.it.queryTimeoutSeconds` (по умолчанию 5) в `GetAssetIT:46`, `SearchAssetsIT:51`, `TraceDependenciesIT:47`, `DeploymentCommitReadIT:39`; `RuntimeFootprintIT:31` задаёт `Duration.ofSeconds(5)` жёстко 🟡. Диспетчер в `JournalDispatcherIT` крутится в фоне (`poll-interval=100ms, batch-size=1, retry-delay=1s`, `JournalDispatcherIT:86-104`); в `AdminEndpointIT` выключен (`archrag.dispatcher.enabled=false`, `:101`), чтобы тест сам решал, когда обрабатывать журнал; в `SyncAcceptanceIT` включён на умолчаниях ради честного замера задержки (`:162`).

#### Z1.2.6 JWT и безопасность в IT 🟢

Три разные схемы — важно не путать, что они проверяют:
1. **`McpTestJwt`** — настоящая проверка: RSA-2048 в памяти, публичный ключ отдаёт локальный `HttpServer` (JWKS `/jwks`, kid `test`) (`McpTestJwt:40-52`). Приложение получает `jwk-set-uri`, `issuer-uri`, `audiences` и **само собирает валидаторы** (подпись, issuer, audience, плюс `azp` в ingestion). Используют: `AdminEndpointIT`, `AdminConsoleIT`, `SyncConsoleIT`, MCP-контекст в `F1–F4`.
2. **Подменённый `JwtDecoder`** на HS256 с общим ключом `0123456789abcdef…` (`@TestConfiguration`: `F1AcceptanceIT:99-106`, `JournalDispatcherIT:81-82`, `EamKeyMigrationIT:100-101`, `SyncAcceptanceIT:114-120`, `F4AcceptanceIT:126-127`) — проверка подписи есть, issuer/audience/azp **выключены** (`issuer-uri=http://unused.invalid` — плейсхолдер). Токен `scope=architecture.admin` без `aud`/`azp`.
3. **Без токенов** — все классы `M`.

Следствие 🟡: реальная цепочка JWT ingestion-сервиса (issuer + audience + `AuthorizedPartyValidator`) работает **только в `AdminEndpointIT`**; в семи классах (`F1–F4` для ingestion-части, `SyncAcceptanceIT`, `EamKeyMigrationIT`, `JournalDispatcherIT`) она заменена заглушкой, а проверки доступа там либо отсутствуют, либо относятся к MCP.

#### Z1.2.7 Прочее

- Фиксированных портов нет: `server.port=0`, `RANDOM_PORT`, эфемерный порт webhook. Исключение по духу — `freePort()` (закрывает сокет до использования, `AdminEndpointIT:74-82`, `SyncAcceptanceIT:123-129`) и «недоступный адаптер» на `127.0.0.1:1` (`AdminEndpointIT:104`, `F1–F4` `control-url`) 🟡: при гонке/нетипичной ОС тест нестабилен.
- Порядок тестов: `@TestMethodOrder(OrderAnnotation)` в 6 классах (см. таблицу), в остальных порядок не нужен 🟢; детали — Z1.6.2.
- Секретов в Git нет: пароли `reader-pass`, `ro-pass`, ключ HS256, секрет webhook — тестовые литералы 🟢 (ловушка 12).

---

### Z1.3 Каталог сценариев по областям

Формат: **поведение** → тесты → ловушки `CLAUDE.md` (№) / документы. Негативные тесты помечены «(−)». Все 299 методов учтены (проверка полноты — внизу раздела).

#### Z1.3.1 Ингест и журнал (Postgres inbox, S3 raw, диспетчер)

`ContainersSmokeIT` — «дымовой» тест среды: Neo4j отвечает на Cypher `ContainersSmokeIT.neo4jAnswersCypher:63`, PostgreSQL на `select 1` `ContainersSmokeIT.postgresAnswersSelect:70`, S3 принимает/отдаёт объект через `S3RawPayloadStore` `ContainersSmokeIT.s3StoragePutsAndGetsObject:79`.

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| Подписанный webhook → 202, строка `inbox_event` `RECEIVED`, raw в S3 читается по ref; повтор того же события → 202, но строка одна | `AdapterInboxIT.webhookIsStoredAsReceivedRowWithRawObjectAndDuplicateAddsNothing:99` | 6 (дедуп `source+eventId`), ADR 0007 |
| Неверная подпись и просроченный timestamp (−1 ч) → 401, в журнал ничего не пишется | `AdapterInboxIT.badSignatureAndStaleTimestampAreRejectedAndNothingIsWritten:123` (−) | аутентификация webhook |
| `pollOnce` завершает snapshot: маркер `snapshot-complete:<run>` записан **последним** в прогоне, записей = данные + 1, checkpoint `eam-poller` создан | `AdapterInboxIT.snapshotEndsWithMarkerOnRealInbox:135` | 9 (маркер после данных) |
| Pending-выборка: `RECEIVED`, «созревший» `RETRYING`, давно прерванный `VALIDATED` — в порядке журнала keyset-ом; свежий `RETRYING` и `PROJECTED` не отдаются; `limit=0` отклоняется | `EventJournalIT.pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset:109` (−) | 14 |
| Дубль `source+eventId` → `DUPLICATE` с исходной версией, строка одна, статус не меняется; тот же `eventId` из другого источника — не дубль | `EventJournalIT.duplicateEventIsNotInsertedTwice:145`, `EventJournalIT.sameEventIdFromAnotherSourceIsAccepted:160` (−) | **6** |
| Переход в `RETRYING` увеличивает `attempts`; `toDlq` → `QUARANTINED` с кодом, ссылкой на raw и записью `dlq_entry`; повтор `toDlq` — одна запись; пустой код/неизвестное событие отклоняются | `EventJournalIT.transitionsAndDlqArePersisted:170`, `EventJournalIT.repeatedDlqKeepsSingleRecord:187`, `EventJournalIT.dlqRejectsBlankCodeAndUnknownEvent:199` (−) | **14** |
| Финальное состояние не откатывается (`PROJECTED` → `RETRYING` запрещён) | `EventJournalIT.finalStateCannotBeRolledBack:211` (−) | 6 |
| Checkpoint переживает «рестарт» журнала, последнее значение побеждает, другой consumer пуст | `EventJournalIT.checkpointSurvivesJournalRestart:226` | 6 (сохранность; связь с коммитом Neo4j — см. `GraphProjectorIT`) |
| Миграции применяются повторно без ошибки (**без единого `assert`**) | `EventJournalIT.migrationsAreIdempotent:237` | Flyway |
| Raw в S3 адресуется по хешу: повторный `put` даёт тот же ref (`raw/<source>/<hash>`), неверный хеш при чтении → ошибка | `EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed:243` (−) | ADR 0007 |
| Читатель журнала (`JournalReader`): keyset по порядку получения, фильтр источника и окна времени, исключает `replay:`-события; `limit` вне 1..`MAX_LIMIT` отклоняется | `EventJournalIT.readerPagesByKeysetInReceivedOrderAndFilters:261`, `EventJournalIT.readerRejectsBadLimit:295` (−) | лимиты |
| События адаптера доходят до графа диспетчером без ручного вызова | `JournalDispatcherIT.eventsAppendedToJournalReachGraphWithoutManualCall:122` | конвейер |
| Событие с невалидным raw → `QUARANTINED`/`INVALID_RAW_PAYLOAD`, **причина не содержит текста payload**, ровно одна открытая запись DLQ; следующие события проецируются (poison-pill не блокирует очередь) | `JournalDispatcherIT.brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected:137` (−) | 11, 12, **14** |
| Неизвестный тип события → `QUARANTINED`/`UNSUPPORTED_EVENT_TYPE`; маркер без raw → `QUARANTINED`/`MARKER_NO_RAW` | `JournalDispatcherIT.unsupportedTypeIsQuarantinedWithCode:160`, `JournalDispatcherIT.markerWithoutRawGoesToDlq:219` (−) | **14** |
| Маркер snapshot tombstone-ит пропавший объект, оставшийся остаётся `active` | `JournalDispatcherIT.snapshotMarkerTombstonesObjectMissingFromRun:172` | **9**, ADR 0020 |
| Маркер, заявляющий 5 объектов при 0 в журнале прогона, **ничего не удаляет**; маркер уходит в `RETRYING` (`RECONCILIATION_TRIGGER_FAILED`, `attempts ≥ 2`) | `JournalDispatcherIT.markerClaimingMoreObjectsThanJournalHoldsDeletesNothing:194` (−) | **9**, 14 |

#### Z1.3.2 Identity (gid, crosswalk, кандидаты, конфликты источников в PG)

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| `find` пуст до `resolve`; повторный `resolve` (в т. ч. новым экземпляром, «rebuild») даёт тот же gid | `IdentityMappingIT.findIsEmptyUntilResolved:55`, `IdentityMappingIT.repeatedResolveReturnsSameGidWithNewMappingInstance:64` | ADR 0003 |
| Один `sourceId` в EAM и SCM — разные сущности | `IdentityMappingIT.sameSourceIdInDifferentSystemsIsNotSameEntity:76` (−) | ADR 0003, 0019 |
| 16 параллельных `resolve` одного ключа сходятся к одному gid | `IdentityMappingIT.concurrentResolveConvergesToOneGid:84` (−) | MERGE по ключу (4) |
| Утверждённый crosswalk даёт EAM- и SCM-записи один gid; присоединение к уже существующему gid; идемпотентность и независимость от порядка | `IdentityMappingIT.crosswalkGivesEamAndScmRecordsOneGid:97`, `IdentityMappingIT.crosswalkAdoptsGidOfAlreadyMappedKey:109`, `IdentityMappingIT.crosswalkIsIdempotentAndOrderInsensitive:119` | ADR 0019 |
| Ключи с разными gid → `CrosswalkConflictException`, ничего не меняется; гонка `resolve`/`approve` завершается общим gid либо чистым откатом | `IdentityMappingIT.crosswalkOfKeysWithDifferentGidsIsRejectedAndChangesNothing:131`, `IdentityMappingIT.concurrentResolveAndApproveEndWithSharedGidOrCleanConflict:144` (−) | ADR 0019 (нет автослияния) |
| Два CMDB-узла с одним hostname (регистр/точка нормализуются) остаются **двумя** узлами, создаётся один `OPEN`-кандидат (HOSTNAME, score 0.60) | `IdentityCandidateIT.sameHostnameInTwoRecordsKeepsTwoNodesAndRecordsOneOpenCandidate:141` | ADR 0019 |
| Имя + владелец → кандидат с двумя признаками; один владелец при разных именах и IT_SYSTEM vs SERVICE с одним именем — не кандидаты | `IdentityCandidateIT.sameNameAndOwnerGivesCandidateWithBothFeatures:165`, `IdentityCandidateIT.sameOwnerWithDifferentNamesGivesNoCandidate:183` (−), `IdentityCandidateIT.itSystemAndServiceWithSameNameAreNotCandidates:197` (−) | порог признаков |
| Связанные crosswalk-ом записи кандидата не порождают; повторная обработка (версия 2) оставляет одного кандидата с прежним `firstSeenAt` | `IdentityCandidateIT.recordsLinkedByCrosswalkShareGidAndGiveNoCandidate:210` (−), `IdentityCandidateIT.replayKeepsOneCandidateAndFirstSeenAt:226` | идемпотентность |
| Tombstone-запись не матчится с поздней; признак чаще `MAX_MATCHES` (50) кандидатов не создаёт | `IdentityCandidateIT.tombstonedRecordDoesNotMatchLaterRecord:246` (−), `IdentityCandidateIT.popularFeatureAboveLimitCreatesNoCandidates:264` (−) | ADR 0020, лимиты |
| Конфликт источников (`source_conflict`): расхождение не-мастера с мастером открывает один `PROPERTY`-конфликт; совпадение закрывает (`RESOLVED`), повторное расхождение снова `OPEN` с прежним `openedAt` | `PostgresSourceConflictsIT.differingNonAuthoritativeValueOpensOneConflict:82`, `PostgresSourceConflictsIT.equalValueResolvesAndReopeningKeepsOpenedAt:102` | ADR 0008 |
| Результат не зависит от порядка прихода; смена значения мастера пересчитывает пару; только не-мастера конфликта не дают | `PostgresSourceConflictsIT.conflictDoesNotDependOnArrivalOrder:122`, `PostgresSourceConflictsIT.masterChangingValueRecomputesPair:135`, `PostgresSourceConflictsIT.onlyNonAuthoritativeAssertionsGiveNoConflict:148` (−) | ADR 0008 |
| Отзыв утверждения (retract) мастера или dissent закрывает конфликт; повтор не меняет строку (в т. ч. `updated_at`); конфликты других gid не затронуты | `PostgresSourceConflictsIT.retractOfDissentResolvesAndRetractOfMasterResolvesToo:157`, `PostgresSourceConflictsIT.repeatedCallLeavesStateUnchanged:173`, `PostgresSourceConflictsIT.conflictsOfOtherGidAreNotAffected:187` (−) | 6, ADR 0020 |
| `RELATION`-конфликты (deploymap `eamSystemId` vs EAM): открываются идемпотентно, закрываются при совпадении; без известного мастера не открываются; пересчёт `PROPERTY` не трогает `RELATION`; на одном gid живут оба вида | `PostgresSourceConflictsIT.relationMismatchOpensIdempotentlyAndResolvesWhenEqual:211`, `PostgresSourceConflictsIT.relationClosesWhenMasterUnknownAndNeverOpensWithoutIt:239` (−), `PostgresSourceConflictsIT.retractResolvesRelationAndPropertyRecomputeLeavesItAlone:249`, `PostgresSourceConflictsIT.relationAndPropertyConflictsCoexistOnOneGid:263` | ADR 0030 |

#### Z1.3.3 Projector (граф Neo4j, `GraphProjectorIT`: 51 тест) и схема

`GraphProjectorIT` (1109 строк) — самый большой класс; тесты идут через `EventProcessor` (`handle` = `journal.append` + `process`, `GraphProjectorIT:144-147`) и через `projector.project` напрямую.

| Группа поведений | Тесты | Ловушки / док |
|---|---|---|
| **Upsert и provenance**: узел `Team` с gid из identity, `isCurrent`, `SourceRecord` (version/active/contentHash), цепочка `SourceSystem-OWNS_RECORD→SourceRecord-ASSERTS{authority=MASTER}→Team`, `SyncRun-PROCESSED→SourceRecord`, checkpoint журнала сдвинут | `GraphProjectorIT.upsertCreatesNodeWithProvenanceAndMovesEventToProjected:199`, `GraphProjectorIT.syncRunIsLinkedOnceAndNotDoubledOnRepeat:853` | 4, 6 |
| **Идемпотентность и версии**: повтор события и повторный `project` той же версии → граф идентичен (`dump`); тот же `ver` другим `eventId` (webhook/polling) → `DUPLICATE`, а не `IGNORED_OLD_VERSION`; версия сравнивается **числом** ("99" < "184"); out-of-order 5→3 и 3→5 сходятся к 5 | `GraphProjectorIT.replayDoesNotChangeGraph:222`, `GraphProjectorIT.webhookAndPollingWithSameVersionAreDuplicateNotOldVersion:242` (−), `GraphProjectorIT.olderVersionIsIgnoredAndComparedNumerically:256` (−), `GraphProjectorIT.outOfOrderDeliveryConvergesToTheNewestVersion:269` (−) | **6** (`IGNORED_OLD_VERSION`) |
| 16 параллельных версий одного объекта в 8 потоках → один `SourceRecord` версии 16 | `GraphProjectorIT.concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion:285` | **4** (MERGE по ключу) |
| **Неполная запись не затирает**: версия 2 без `description`/`criticality` сохраняет известное и обновляет `name` | `GraphProjectorIT.partialRecordDoesNotEraseKnownFields:304` (−) | **5** |
| **Authority**: не-мастер (CMDB) по `Service` — `APPLIED`, `skippedProperties`, значения мастера целы, два `ASSERTS` (`MASTER`/`SUPPLEMENTARY`); не-мастерская связь `DEPENDS_ON` пропущена и отражена в `skippedRelations` | `GraphProjectorIT.nonAuthoritativeSourceDoesNotReplaceCanonicalValue:320` (−), `GraphProjectorIT.nonAuthoritativeRelationIsSkippedAndReported:659` (−) | ADR 0008 |
| **Темпоральные связи** (`validFrom`/`validTo`): `OWNED_BY` получает `validFrom`, повтор версии не переоткрывает; смена `hosts`/`ownerTeam`/service/environment/`systemCode`/`repositoryId` закрывает старую связь временем события и открывает новую; хост, вернувшийся позже, переоткрывается без дубля; связь, подтверждённая другой записью, не закрывается | `GraphProjectorIT.temporalRelationGetsValidFromAndIsNotReopenedByRepeat:343`, `GraphProjectorIT.replacedHostClosesOldRunsOnAtEventTimeAndKeepsNewOpen:377`, `GraphProjectorIT.changedOwnerTeamClosesOldOwnedBy:391`, `GraphProjectorIT.unknownNewHostStillClosesOldRunsOn:439`, `GraphProjectorIT.relationReassertedByAnotherRecordIsNotClosed:450` (−), `GraphProjectorIT.hostReturningInLaterVersionReopensRunsOn:467`, `GraphProjectorIT.relationValidFromDefaultsToEventTimeAndRunsOnLinksDeploymentToHost:481`, `GraphProjectorIT.changedServiceAndEnvironmentOfDeploymentCloseOldStructuralEdges:501`, `GraphProjectorIT.structuralEdgesGetValidFromFromEventTime:528`, `GraphProjectorIT.changedSystemOfServiceClosesOldDecomposedInto:542`, `GraphProjectorIT.changedRepositoryOfServiceClosesOldImplementedIn:560` | 10 (`datetime`/UTC), ADR 0028 |
| Повтор версии и версия без поля `hosts` ничего не закрывают; **пустой список `hosts` неотличим от отсутствующего** и тоже ничего не закрывает (задокументированное ограничение) | `GraphProjectorIT.repeatedVersionClosesNothing:406` (−), `GraphProjectorIT.partialRecordWithoutHostsClosesNothing:416` (−), `GraphProjectorIT.emptyHostsListClosesNothingKnownLimitation:428` (−) | **5** |
| Tombstone закрывает структурные связи; миграция `TemporalRelationMigration` проставляет отсутствующий `validFrom` (из `firstSeenAt`) и **идемпотентна** (повтор → 0) | `GraphProjectorIT.tombstoneClosesStructuralEdges:576`, `GraphProjectorIT.temporalRelationMigrationFillsMissingValidFromAndIsIdempotent:591` (−) | ADR 0020, миграции |
| **Связи и подтипы**: ссылка на отсутствующую цель не создаёт связь и фантомный узел; SCM-сервис строит `DECOMPOSED_INTO` по `eamCode`; смена `kind` у `ComputeInstance` оставляет один узел и меняет метку подтипа | `GraphProjectorIT.relationToUnknownTargetIsNotBuiltAndCreatesNoPhantomNode:633` (−), `GraphProjectorIT.scmServiceRecordBuildsDecomposedIntoFromEamSystem:644`, `GraphProjectorIT.computeInstanceSubtypeChangeKeepsOneNode:683` | **4** |
| `SERVICE_DEPENDENCY` строит `DEPENDS_ON`; тот же `version` → `DUPLICATE`, меньшая → `IGNORED_OLD_VERSION`; tombstone закрывает ребро (`validTo=T2`), сервисы остаются текущими; «пропавшая» зависимость закрывается tombstone-ом (Reconciler не вызывается, имитация) | `GraphProjectorIT.serviceDependencyBuildsDependsOnEdgeAndIsIdempotentAndVersioned:706` (−), `GraphProjectorIT.serviceDependencyTombstoneClosesEdgeAndKeepsServicesCurrent:733`, `GraphProjectorIT.missingServiceDependencyIsFoundByReconcilerAndItsEdgeIsClosed:748` | **6**, 9 (🟡: Reconciler не вызывается) |
| **Tombstone**: `active=false`, версия 2, узел `isCurrent=false` + `deletedAt`, связи закрыты, сосед цел; новее версия реактивирует; узел с двумя мастер-записями живёт, пока жива вторая; delete раньше upsert блокирует старый upsert (`IGNORED_OLD_VERSION`); delete старой версии игнорируется | `GraphProjectorIT.tombstoneDeactivatesRecordArchivesNodeAndClosesRelations:773`, `GraphProjectorIT.upsertWithNewerVersionAfterTombstoneReactivates:794` (−), `GraphProjectorIT.tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains:810`, `GraphProjectorIT.deleteArrivingBeforeUpsertBlocksOlderUpsertAndCreatesNoNode:827` (−), `GraphProjectorIT.deleteWithOlderVersionIsIgnored:839` (−) | **6**, ADR 0020 |
| **Сбои и карантин**: маркер `snapshot-complete` передаётся `ReconciliationTrigger` и в граф не пишется; `ServiceUnavailableException` из проекции → `RETRYING`/`GRAPH_UNAVAILABLE`, **checkpoint не сдвинут**, граф пуст, повтор → `PROJECTED` и сдвиг; пустой payload TEAM → `QUARANTINED`/`MISSING_REQUIRED_FIELD`, граф пуст | `GraphProjectorIT.snapshotCompleteIsHandedToReconciliationAndNotProjected:862`, `GraphProjectorIT.graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint:875` (−), `GraphProjectorIT.invalidPayloadIsQuarantinedWithCodeAndWritesNothing:916` (−) | **6** (checkpoint после коммита), **14** |
| **Отложенные связи** (`PendingRelation`, E1.14): зависимость до обоих сервисов достраивается после появления второго; SCM-сервис до системы EAM, система до команды — аналогично; новая версия заменяет отложенную; tombstone referrer-а удаляет pending; повторная проекция концов не дублирует ребро; повтор той же версии referrer-а pending не удаляет | `GraphProjectorIT.dependencyBeforeBothServicesAppearsAfterSecondService:944`, `GraphProjectorIT.scmServiceBeforeEamSystemGetsDecomposedInto:968`, `GraphProjectorIT.systemBeforeTeamGetsOwnedBy:988`, `GraphProjectorIT.newReferrerVersionReplacesDeferredRelation:1002`, `GraphProjectorIT.tombstoneOfReferrerRemovesDeferredRelation:1021` (−), `GraphProjectorIT.repeatedEndpointProjectionDoesNotDuplicateEdge:1038` (−), `GraphProjectorIT.sameVersionReplayOfReferrerKeepsDeferredRelation:1055` (−) | 4, 6 |
| **Система EAM сервиса (H.5)**: `eamSystemOfService` возвращает единственную декомпозирующую систему; пусто без системы и при двух | `GraphProjectorIT.eamSystemOfServiceFindsTheSingleDecomposingSystem:1074`, `GraphProjectorIT.eamSystemOfServiceIsEmptyWithoutSystem:1085` (−), `GraphProjectorIT.eamSystemOfServiceIsEmptyWhenTwoSystemsDecomposeIt:1094` (−) | ADR 0030 |

**`Neo4jSchemaIT`** (схема Community): после двойного `apply` ровно ожидаемый набор constraint-ов (13 `*_gid`, `source_record_key`, `source_system_code`, `sync_run_id`, `pending_relation_key`, `environment_code`) и индексов `asset_text`, `it_system_eam_code` без дублей `Neo4jSchemaIT.applyTwiceCreatesEveryObjectExactlyOnce:48`; составной UNIQUE `(source, sourceType, sourceId)` отклоняет дубль, но принимает тот же `sourceId` с другим `sourceType` `Neo4jSchemaIT.duplicateSourceRecordKeyIsRejectedButOtherSourceTypeAccepted:61` (−); дубли `Service.gid` и `Environment.code` отклоняются `Neo4jSchemaIT.duplicateServiceGidAndEnvironmentCodeAreRejected:72` (−); FULLTEXT `asset_text` находит `ITSystem` по имени `Neo4jSchemaIT.fulltextFindsItSystemByName:82`. Ловушка 4, ADR 0004 (никаких NODE KEY/existence-constraints) 🟢; отсутствие платных конструкций тестом не утверждается 🟡.

#### Z1.3.4 Конфликты источников в графе и инварианты платформы

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| Неавторитетное значение (CMDB `LOW` при мастере EAM `HIGH`): в графе остаётся `HIGH`, один `OPEN`-конфликт в PG, маркер `conflicts=[criticality]` **только** на ребре `ASSERTS` записи CMDB | `SourceConflictIT.nonAuthoritativeValueOpensConflictAndMarksOnlyDissentEdge:199` (−) | ADR 0008 |
| Порядок прихода не важен; совпавшее значение (новой версией CMDB или смена мастера) закрывает конфликт и снимает маркер; одинаковые значения не открывают | `SourceConflictIT.reverseOrderGivesTheSameOutcome:221` (−), `SourceConflictIT.agreeingValueResolvesConflictAndClearsMarker:241`, `SourceConflictIT.masterChangeClearsMarkerOnDissentEdge:255`, `SourceConflictIT.sameValuesNeverOpenConflict:268` (−) | — |
| Reconcile snapshot CMDB без записи деактивирует её, снимает assertion в PG, конфликт → `RESOLVED`, маркеры сняты, значение мастера цело | `SourceConflictIT.reconciliationTombstoneResolvesConflictAndClearsMarkers:280` | **9** (🟡 см. Z1.6.4: маркер подделан) |
| «Rebuild»: после очистки графа и переигровки событий конфликт и маркер те же (PG не трогали) | `SourceConflictIT.rebuildKeepsNodeConflictAndMarker:328` | критерий 4 (🟡: rebuild смоделирован) |
| Инвариант платформы ИТ-системы (ADR 0031): решение раньше/позже системы с другим `platformId` открывает `RELATION`-конфликт `platformId` (dissent — система, master — решение); исправление версией системы или решения и tombstone системы → `RESOLVED`; граф строится по решению, прямого ребра «система→платформа» нет; согласованные данные конфликтов не дают | `PlatformInvariantIT.solutionBeforeSystemOpensConflict:173` (−), `PlatformInvariantIT.systemBeforeSolutionOpensConflictWhenSolutionArrives:182` (−), `PlatformInvariantIT.newSystemVersionWithCorrectedPlatformResolvesConflict:193`, `PlatformInvariantIT.newSolutionVersionWithSystemPlatformResolvesConflict:204`, `PlatformInvariantIT.systemTombstoneResolvesConflict:215`, `PlatformInvariantIT.graphIsBuiltBySolutionAndHasNoDirectSystemToPlatformEdge:226` (−), `PlatformInvariantIT.baselineHasNoPlatformConflicts:240` (−) | ADR 0031, 0019, 0020 |
| Deploy map: `eamSystemId` развёртывания расходится с системой сервиса → `OPEN` `RELATION`-конфликт и `ASSERTS.conflicts=['eamSystemId']`; совпадение конфликта не даёт; deploy map раньше SCM — конфликт открывается при появлении сервиса; исправление чарта → `RESOLVED`, регресс → снова `OPEN`; tombstone → `RESOLVED` | `DeployedFromIT.systemMismatchOpensRelationConflict:226`, `DeployedFromIT.matchingSystemOpensNoConflict:242` (−), `DeployedFromIT.deployMapBeforeScmOpensConflictWhenScmArrives:253` (−), `DeployedFromIT.correctedChartResolvesConflictAndRegressionReopensIt:266`, `DeployedFromIT.deploymentTombstoneResolvesConflict:282` | ADR 0030 |

#### Z1.3.5 Проекции EAM-API и deploy map, миграция ключа

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| Развёртывание привязывается к репозиторию (`DEPLOYED_FROM` с `sourceCommit`, `repositoryUrl`, `eamSystemId`), сервис находится по репозиторию через `IMPLEMENTED_IN` | `DeployedFromIT.deploymentIsBoundToRepositoryAndServiceFoundByUrl:204` | 4 |
| Новый commit закрывает старое `DEPLOYED_FROM` (`validTo` = время события UTC) и открывает новое; тот же commit новой версией не меняет рёбра; отложенная связь достраивается при появлении репозитория; при неактивном репозитории старое ребро закрывается, новая связь откладывается; неверный commit → `QUARANTINED`/`INVALID_PAYLOAD`, граф цел | `DeployedFromIT.newCommitClosesOldDeployedFromAndOpensNewOne:294`, `DeployedFromIT.sameCommitInNewVersionChangesNothing:312` (−), `DeployedFromIT.deferredDeployedFromIsBuiltWhenRepositoryArrives:327` (−), `DeployedFromIT.commitChangeWhileRepositoryIsUnknownClosesOldEdge:344` (−), `DeployedFromIT.invalidCommitIsQuarantinedAndGraphIsUntouched:360` (−) | 10, 14 |
| Срез `ITSystem → Solution → Platform` строится в любом порядке прихода (недостающие цели откладываются как `PendingRelation`); SCM-сервис до/после EAM-системы; неизвестный `systemCode` остаётся отложенным без фантомного узла | `EamApiProjectionIT.verticalSliceItSystemSolutionPlatformIsBuiltInAnyOrder:158`, `EamApiProjectionIT.scmServiceBeforeEamIsLinkedToItSystemWhenItArrives:181`, `EamApiProjectionIT.eamBeforeScmServiceIsLinkedImmediatelyToRealKey:197`, `EamApiProjectionIT.unknownSystemCodeStaysDeferredAndCreatesNoPhantomNode:209` (−) | 4, ADR 0024 |
| Смена `platform_id` закрывает старую `USES_PLATFORM`; повтор `ver` — не карантин, старая `ver` → `IGNORED_OLD_VERSION` (имя не затирается); одинаковое `name` при разных `id` — два узла; неизвестная `criticality` пишется в `criticalityRaw` и **не затирает** известную; tombstone платформы → `active=false` | `EamApiProjectionIT.changedReferenceClosesOldRelationAndOpensNewOne:220`, `EamApiProjectionIT.repeatedVerIsNoOpAndOlderVerIsIgnored:233` (−), `EamApiProjectionIT.sameNameDifferentIdsAreTwoNodes:242` (−), `EamApiProjectionIT.unknownCriticalityIsStoredRawAndDoesNotOverwriteKnownValue:250` (−), `EamApiProjectionIT.tombstonedPlatformDisappearsFromActiveRecords:261` | **5**, **6** |
| Миграция ключа EAM (UNLOCKER-255), строго по порядку m1→m5: на старом стенде `ITSystem` с ключами `EAM-1042/EAM-2001`; новый составной адаптер читает eam-api при старом checkpoint (`ADVANCED`); первый snapshot tombstone-ит старые ключи, `TEAM`/`SERVICE_DEPENDENCY` остаются; `POST /admin/rebuild?confirm=true` → нет активных `EAM-*`, нет дублей, старые узлы в архиве; нет карантина/DLQ, `source_conflict` пуст, кандидаты только «старый↔преемник» | `EamKeyMigrationIT.m1_legacyStandHasItSystemsWithOldKeys:175`, `EamKeyMigrationIT.m2_newAdapterReadsEamApiDespiteOldCheckpoint:189`, `EamKeyMigrationIT.m3_reconcileTombstonesOldKeysAndKeepsTeamAndDependencies:207`, `EamKeyMigrationIT.m4_rebuildHasNoItSystemWithOldKeysAndNoDuplicates:225`, `EamKeyMigrationIT.m5_provenanceAndConflictStoresAreConsistent:250` | **9**, 14, ADR 0019/0020 |

#### Z1.3.6 Query-core (`graph-query-core`): чтение графа без HTTP

Все классы этой группы вызывают `GraphQueryExecutor` под учёткой `mcp-reader` напрямую (HTTP, JWT и scope здесь нет — см. Z1.3.9 и матрицу). Граф засевается либо настоящим `GraphProjector` через `AssetFixtures`, либо голым Cypher.

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| Шаблоны с `CREATE`/`SET`/`DETACH DELETE` принимаются конструктором, но на первом `execute` падают на EXPLAIN-проверке («not read-only») и остаются закрытыми; граф не меняется | `GraphQueryCoreIT.writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed:79` (−) | **3** |
| Сессия `READ_SESSION_CONFIG` отвергает `CREATE` серверным кодом `Neo.ClientError.Statement.AccessMode` (учётка Community без ролей — защита именно режимом сессии) | `GraphQueryCoreIT.readSessionConfigRejectsWritesAtDatabaseLevel:112` (−) | **3**, ADR 0005 |
| Конструктор исполнителя не обращается к БД; публичный API не принимает произвольный Cypher (единственный `String`-параметр — ID шаблона, проверка reflection); неизвестный шаблон, отсутствующий/лишний параметр и внешний `limit` отклоняются до БД | `GraphQueryCoreIT.executorConstructionDoesNotTouchDatabase:103`, `GraphQueryCoreIT.publicApiHasNoMethodAcceptingCypher:137`, `GraphQueryCoreIT.unknownTemplateAndBadParametersFailBeforeDatabase:150` (−) | **1** |
| Шаблоны, возвращающие сырые узлы/связи/пути (в т. ч. во вложенных списках/map), запрещены | `GraphQueryCoreIT.nodesRelationshipsAndPathsInResultAreForbidden:123` (−) | 11 |
| Бюджет: строки (5 из 20 → `truncated`), байты (100 B), запрошенный бюджет зажимается потолком `QueryLimits`, тяжёлый запрос при таймауте 100 мс → `QueryTimeoutException` быстрее 20 с | `GraphQueryCoreIT.rowBudgetTruncates:161`, `GraphQueryCoreIT.byteBudgetTruncates:177`, `GraphQueryCoreIT.requestedBudgetIsClampedToConfiguredCeiling:187`, `GraphQueryCoreIT.timeoutGivesQueryTimeoutException:201` (−) | лимиты |
| `{maxDepth}` подставляется **числом** из бюджета: при глубине 2 достижимо 2 узла, при 3 — три | `GraphQueryCoreIT.traversalDepthIsBoundedByMaxDepth:214` | **2** |
| `get_asset`: карточка (свойства, времена, активные источники с authority, без `contentHash`); закрытый узел возвращается `isCurrent=false`; неизвестный gid и gid служебного `SourceRecord` — пусто | `GetAssetIT.cardHasPropertiesTimesAndSourcesWithAuthority:106`, `GetAssetIT.closedNodeIsReturnedNotCurrentWithInactiveRecord:124`, `GetAssetIT.unknownGidAndServiceNodeGidReturnNothing:137` (−) | 1 (allowlist меток) |
| `asset_relations`: открытые связи на один уровень в обе стороны только из allowlist; закрытая (`validTo` в прошлом) не возвращается; бюджет узлов → `truncated` | `GetAssetIT.relationsAreCurrentOneLevelBothDirectionsFromAllowlist:146`, `GetAssetIT.closedRelationIsNotReturned:159` (−), `GetAssetIT.relationsRespectNodeBudget:169` (−) | 1, лимиты |
| `search_assets`: точный gid/`sourceId` ранжируется выше fulltext; в `sources` только активные записи; fulltext по слову; узел из обеих веток один раз; фильтр типов; `Repository` — только точным совпадением | `SearchAssetsIT.exactGidMatchComesFirstAboveFulltextHits:139`, `SearchAssetsIT.exactSourceIdMatchRanksAboveFulltextAndListsActiveSourcesOnly:150`, `SearchAssetsIT.fulltextFindsBySearchableTextAndExactStillWins:159`, `SearchAssetsIT.nodeFoundByExactAndFulltextAppearsOnceWithExactType:168`, `SearchAssetsIT.typeFilterRestrictsResults:180` (−), `SearchAssetsIT.repositoryIsFoundOnlyByExactMatch:186` (−) | 1 |
| Фильтр окружения идёт по открытым связям (закрытая `IN_ENVIRONMENT` не считается); лимит 1 → `truncated`; нетекущий узел не находится; спецсимволы Lucene и ввод без текстовых символов не роняют запрос; узел вне поисковых типов не отдаётся | `SearchAssetsIT.environmentFilterFollowsRelationsAndExcludesOthers:192` (−), `SearchAssetsIT.closedStructuralEdgesDoNotMatchEnvironmentFilter:203` (−), `SearchAssetsIT.limitAndTruncated:215` (−), `SearchAssetsIT.nonCurrentNodeIsNotReturned:225` (−), `SearchAssetsIT.luceneSpecialCharactersDoNotFail:231` (−), `SearchAssetsIT.nonTextInputRunsOnlyExactBranches:239` (−), `SearchAssetsIT.nodeOutsideSearchableTypesIsNotReturnedByExactSourceId:247` (−) | **1**, лимиты |
| `trace_*`/`impact`: обход вниз/вверх по allowlist типов, пути с провенансом шагов; цикл `DEPENDS_ON` завершается, связь в пути не повторяется; `maxPaths` → `truncated`; `maxDepth` ограничивает; закрытая связь и нетекущий узел не проходятся; фильтр типов связей сужает обход; неизвестный gid → пусто | `TraceDependenciesIT.downstreamFollowsDecompositionDeploymentRunsOnAndHostedOn:137`, `TraceDependenciesIT.upstreamGoesAgainstArrowsFromComputeInstanceToSystem:155`, `TraceDependenciesIT.dependencyCycleTerminatesAndDoesNotRepeatRelationInPath:164` (−), `TraceDependenciesIT.maxPathsBelowPathCountIsTruncated:173` (−), `TraceDependenciesIT.depthBoundsTheTraversal:183` (−), `TraceDependenciesIT.closedRelationIsNotTraversed:190` (−), `TraceDependenciesIT.relationTypesFilterNarrowsTheTraversal:197` (−), `TraceDependenciesIT.notCurrentNodeIsNotTraversed:204` (−), `TraceDependenciesIT.unknownGidGivesNoPathsAndNoAssetCard:213` (−) | **2**, лимиты |
| `impact` от VM: затронуты сервис и система, но не `ledger-api`; в путях провенанс (`assertedBy*`, `sourceFetchedAt`, `sourceActive`); `DEPENDS_ON`-потребитель затронут, владение `OWNED_BY` не идёт; фильтр окружения отсекает чужие развёртывания, закрытая `IN_ENVIRONMENT` игнорируется, путь без Deployment проходит фильтр; закрытая `RUNS_ON` → пусто; отражены `lastSeenAt`, конфликты ASSERTS и неактивный источник | `TraceDependenciesIT.stoppingVmReachesServicesAndSystemsWithStepProvenance:259`, `TraceDependenciesIT.dependsOnConsumerOfAffectedServiceIsAffectedAndOwnershipIsNotFollowed:278` (−), `TraceDependenciesIT.environmentFilterCutsDeploymentsOfOtherEnvironments:285` (−), `TraceDependenciesIT.environmentFilterIgnoresClosedEnvironmentLink:300` (−), `TraceDependenciesIT.pathWithoutDeploymentPassesEnvironmentFilter:311`, `TraceDependenciesIT.closedRunsOnGivesNoImpact:320` (−), `TraceDependenciesIT.freshnessConflictsAndInactiveSourceAreProjected:326` | критерий 7 |
| На плотном графе (30×30 `DEPENDS_ON`) impact и upstream-trace усекаются (`truncated=true`) в пределах таймаута | `TraceDependenciesIT.denseGraphImpactIsTruncatedWithinTimeout:375`, `TraceDependenciesIT.denseGraphUpstreamTraceIsTruncatedWithinTimeout:385` (−) | **2**, лимиты (🟡 время явно не измеряется) |
| `DEPLOYED_FROM` в чтении: один ряд с текущим commit/репозиторием и провенансом ребра (закрытое не размножает); без `DEPLOYED_FROM` ряд остаётся с `null`; `asset_relations` и `trace_downstream` видят только открытое ребро | `DeploymentCommitReadIT.footprintReturnsCurrentCommitRepositoryAndEdgeProvenanceInOneRow:114`, `DeploymentCommitReadIT.deploymentWithoutDeployedFromGivesNullFieldsAndStillOneRow:130`, `DeploymentCommitReadIT.getAssetRelationsShowOnlyOpenDeployedFromBothDirections:143`, `DeploymentCommitReadIT.traceDownstreamReachesRepositoryOverOpenDeployedFromOnly:161` | — |
| `find_runtime_footprint`: несколько развёртываний одного сервиса — отдельные ряды; фильтр окружения, сервис без развёртываний остаётся; VM на физсервере и развёртывание прямо на нём; активные источники и провенанс `HAS_DEPLOYMENT/RUNS_ON/HOSTED_ON`; `RUNS_ON` на `Namespace` не отдаётся; закрытые связи и нетекущие узлы исключены; не-система/неизвестный gid → пусто | `RuntimeFootprintIT.severalDeploymentsOfOneServiceInOneEnvironmentAreSeparateRows:107`, `RuntimeFootprintIT.environmentFilterNarrowsToProdAndServiceWithoutDeploymentsIsKept:121`, `RuntimeFootprintIT.vmHostedOnPhysicalServerAndDeploymentOnPhysicalServerDirectly:137`, `RuntimeFootprintIT.nodeAndEdgeProvenanceIsReturnedAndInactiveSourcesAreSkipped:175`, `RuntimeFootprintIT.runsOnNamespaceIsNotReturned:197` (−), `RuntimeFootprintIT.closedRunsOnAndNonCurrentNodesAreExcluded:213` (−), `RuntimeFootprintIT.closedStructuralEdgesAreExcluded:239` (−), `RuntimeFootprintIT.closedUnknownAndNonSystemGidsGiveNoRows:259` (−) | 1 |
| Холодный первый вызов footprint укладывается в 3 с при продовом бюджете 5 с (UNLOCKER-216) | `RuntimeFootprintIT.coldFirstCallFitsProductionTimeout:268` | лимиты (🟡 время) |
| `explain_provenance` (бин-tool как Java-метод): два источника — мастер первым, SCM `SUPPLEMENTARY` с `conflictProperties=['criticality']`, состояние `OPEN`; фильтр по свойству; согласные источники → `NONE`; после snapshot SCM без записи она `active=false`, актив остаётся текущим, конфликт очищен; неизвестный gid → `IllegalArgumentException("asset not found")` без эха значения | `ExplainProvenanceIT.twoSourcesShowMasterAndOpenCriticalityConflict:178`, `ExplainProvenanceIT.propertyFilterKeepsOnlyThatPropertyConflict:209` (−), `ExplainProvenanceIT.agreeingSourcesHaveNoConflict:224` (−), `ExplainProvenanceIT.tombstonedSourceIsInactiveAndConflictIsCleared:237`, `ExplainProvenanceIT.unknownGidIsAssetNotFound:269` (−) | **9**, 11 |

#### Z1.3.7 Консоль (`access/admin-console`): HTTP-слой под реальным JWT

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| `/api/graph/stats` — узлы по меткам и связи по типам (нули для пустых), `lastUpdatedAt`; `search` — fulltext + точный gid + `limit`→`truncated`; карточка узла с `SourceRecord` (authority, `active`), 404 для неизвестного gid | `AdminConsoleIT.statsCountNodesByLabelRelationsByTypeAndLastUpdate:180`, `AdminConsoleIT.searchFindsByFulltextAndByExactGid:195`, `AdminConsoleIT.cardShowsPropertiesTimesAndAssertingSourceRecords:210` (−) | 1, лимиты |
| Окрестность: глубина 1 и 2, фильтр `relTypes`, `DEPLOYED_FROM` принимается; `depth=3` → 400 (до любого запроса, отказ, не clamp); бюджет `max-nodes=3` → `truncated` | `AdminConsoleIT.neighborhoodDepthOneAndTwoWithRelTypeFilter:228`, `AdminConsoleIT.neighborhoodAcceptsDeployedFromRelType:246`, `AdminConsoleIT.depthThreeIsRejectedBeforeAnyQuery:265` (−), `AdminConsoleIT.neighborhoodOverNodeBudgetIsTruncated:272` (−) | **2**, лимиты |
| Все шаблоны консоли и три asset-шаблона проходят EXPLAIN → `READ_ONLY` под `console-reader`; искусственный шаблон `CREATE` отвергается, узлов не прибавилось | `AdminConsoleIT.everyConsoleTemplatePassesReadOnlyExplainAndWritingTemplateIsClosed:280` (−) | **3** |
| Роль PG `archrag_console_ro` читает `inbox_event`, но `INSERT/UPDATE/DELETE` и чтение `identity_mapping` падают с SQLSTATE `42501`; пул консоли имеет `default_transaction_read_only = on`; миграции идемпотентны при существующей роли (**без `assert`**) | `SyncConsoleIT.consoleRoleCannotWriteOrReadOtherTables:183` (−), `SyncConsoleIT.connectionsDefaultToReadOnlyTransactions:251`, `SyncConsoleIT.migrationIsIdempotentForExistingRole:200` | **3** (на уровне PG GRANT), ADR 0026 |
| Без токена → 401; токен `architecture.read` → 403 на `/api/sync/events` и `/api/audit` (**единственные негативные проверки доступа консоли**) | `SyncConsoleIT.withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized:212` (−) | **13** (🔴 неполно, Z1.6.1) |
| Каждое SQL-чтение пишется в read-аудит `archrag.audit.console` (endpoint, `sql:inbox_event`, число строк); большие `request/result` (6000 символов) не отдаются (флаги `…Truncated`) | `SyncConsoleIT.sqlReadsAreRecordedInReadAudit:221`, `SyncConsoleIT.oversizedAuditResultIsNotReturned:240` (−) | 11 |
| Журнал событий: фильтры source/status/период, порядок убывания времени; пагинация и потолок `size=201` → 400; метаданные события содержат `payloadRef`/`contentHash`, но **не payload**; детали с DLQ-историей, неизвестное → 404; DLQ-список с `replayedAt=null`; сводка источников (checkpoint, счётчики по статусам, `lagSeconds`, последний `SyncRun`, нули для пустого источника); конфликты (по умолчанию `OPEN`) и кандидаты; аудит операций с `request/result` | `SyncConsoleIT.eventsFilterBySourceStatusAndPeriod:264`, `SyncConsoleIT.eventsPaginateAndSizeIsCapped:275` (−), `SyncConsoleIT.eventMetadataHasRefAndHashButNoPayload:289` (−), `SyncConsoleIT.eventDetailCarriesDlqHistoryAndUnknownIsNotFound:301` (−), `SyncConsoleIT.dlqListsEntriesWithReason:312`, `SyncConsoleIT.sourcesShowCheckpointCountsLastProjectedLagAndLatestSyncRun:324`, `SyncConsoleIT.conflictsAndCandidatesAreListedAndFiltered:347`, `SyncConsoleIT.auditListsOperationsWithRequestParameters:367` | 11, 14, лимиты |

#### Z1.3.8 Admin REST ingestion (`/admin/*`) — `AdminEndpointIT`

Единственный класс с **настоящей** цепочкой JWT ingestion (подпись + issuer + audience + `azp`-allowlist), поэтому здесь же проверяется безопасность. Четыре настоящих адаптера над заглушками наполняют журнал, обработку делает сам тест (`processAllReceived`, диспетчер выключен).

| Поведение | Тесты | Ловушки / док |
|---|---|---|
| `POST /admin/rebuild?confirm=true` на очищенном графе даёт эквивалентный канонический дамп; в `admin_audit` запись `REBUILD/SUCCEEDED`; в журнал добавляются `replay:*`, исходные не меняются; отложенные связи воспроизводятся без остатка `PendingRelation` | `AdminEndpointIT.rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt:256`, `AdminEndpointIT.rebuildReproducesRelationsThatWereDeferredUntilTheirEndpointsArrived:273` | критерий 4, 6 |
| Без `confirm` (и `confirm=false`) rebuild → 400, граф цел | `AdminEndpointIT.rebuildWithoutConfirmIsRejectedAndGraphIsUntouched:292` (−) | защита разрушающей операции |
| Replay с тем же `replayId` не плодит строки (`replay:<id>:…`) и не меняет граф, аудит фиксирует 2 вызова; после полной потери графа replay по четырём источникам восстанавливает его; replay пустого диапазона → `total:0` | `AdminEndpointIT.replayIsIdempotentForSameReplayIdAndRestoresLostProjection:303` (−), `AdminEndpointIT.replayOfEmptyRangeDoesNothing:334` (−) | **6**, критерий 3 |
| `POST /admin/crosswalks` по элементам: валидный `APPLIED`, связь двух разных gid `CONFLICT`, неизвестный source/пустая причина `INVALID`; аудит ≥ 4 записей с `actor=operator-1`; повтор идемпотентен | `AdminEndpointIT.crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem:347` (−) | ADR 0019 |
| Токен чужого audience → 401 на rebuild и replay; токен клиента `archrag-admin-console` (azp вне allowlist) с верным audience+scope → 401, `archrag-demo` → 200; без токена 401, без scope / `architecture.read` → 403, мусорный токен 401 | `AdminEndpointIT.tokenForForeignAudienceIsUnauthorized:375` (−), `AdminEndpointIT.tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope:383` (−), `AdminEndpointIT.scopeIsEnforced:393` (−) | **13** |
| Пока удерживается `AdminLock`, rebuild и replay → 409; после освобождения replay 200 | `AdminEndpointIT.concurrentAdminOperationIsRejectedWith409:403` (−) | ADR 0013 |
| Объект, пропавший из источника, после полного snapshot получает tombstone (событие `reconcile:…` → `PROJECTED`), и tombstone **переживает rebuild**; `POST /admin/reconcile/eam` проксируется в control-контекст адаптера (202), пишется маркер `snapshot-complete.v1` и аудит `RECONCILE`; неизвестный источник → 404, недоступный адаптер → 502, `architecture.read` → 403, без токена → 401 | `AdminEndpointIT.reconcileTombstoneSurvivesRebuild:413`, `AdminEndpointIT.reconcileProxiesToAdapterControlContext:435`, `AdminEndpointIT.reconcileUnknownSourceIs404UnreachableAdapterIs502AndScopeIsEnforced:455` (−) | **9**, 13, 14 |

#### Z1.3.9 Приёмка F1–F4 (критерий 5: MCP по Streamable HTTP) — сквозной каркас

Одинаково для всех четырёх классов 🟢 (`F1AcceptanceIT:75-97,141-185`): заглушки (`StubSources.seeded(clock)`) → реальные адаптеры EAM/SCM/CMDB/DEPLOY_MAP → `pollOnce()` с ожиданием `SNAPSHOT_COMPLETED` → PostgreSQL-журнал + S3 → диспетчер ingestion в `@SpringBootTest` → ожидание пустой очереди (нет `RECEIVED/RETRYING/VALIDATED/NORMALIZED/RESOLVED`) и **нуля `QUARANTINED`** → ожидание индексов Neo4j `ONLINE` → создание Neo4j-пользователя `mcp-reader` → второе приложение (MCP, `McpServerApplication`) с протоколом `STATELESS` на `/mcp`, `tool-scopes.<tool>=architecture.read` для каждого tool, лимитами `max-depth=6, max-nodes=500, max-paths=50, timeout=5s, max-response-bytes=512KB` (`F1AcceptanceIT:191-221`). Клиент — `McpSyncClient` на `HttpClientStreamableHttpTransport` с `Authorization: Bearer <McpTestJwt.token("architecture.read")>`. Негативные проверки доступа идут сырым HTTP `POST /mcp` (`tools/call`), без MCP-клиента.

| Класс | Фича (док) | Сценарий (порядок `@Order`) | Тесты |
|---|---|---|---|
| `F1AcceptanceIT` (8) | F1 «поиск и карточка», UNLOCKER-181/184 (`docs/01-development-order.md:40`) | s1 `tools/list`; s2 fulltext по имени → `ITSystem` первым; s3 точный `EAM-1042` → тот же gid; s4 фильтр `types`+`environment`; s5 `limit=1` → `truncated`; s6 карточка со связями; s7 неизвестный gid → tool error без стектрейса; s8 без scope 403, без токена 401 | `F1AcceptanceIT.s1_toolsListContainsSearchAndGet:242`, `F1AcceptanceIT.s2_searchByNameIsFulltext:251`, `F1AcceptanceIT.s3_searchBySourceIdIsExact:268`, `F1AcceptanceIT.s4_typeAndEnvironmentFilter:280`, `F1AcceptanceIT.s5_limitTruncates:289`, `F1AcceptanceIT.s6_getAssetCardWithRelations:301`, `F1AcceptanceIT.s7_unknownGidIsToolErrorWithoutStacktrace:337` (−), `F1AcceptanceIT.s8_accessWithoutScopeOrTokenIsDenied:350` (−) |
| `F2AcceptanceIT` (9) | F2 «runtime footprint», UNLOCKER-185/187 (`:41`) | s1 `tools/list`; s2 footprint Payments Core/prod: сервис→1 Deployment (DEPLOYMAP)→1 VM (CMDB), `stale=false`; s3 окружение без развёртываний; **s4 изменение deploy map в заглушке + `pollOnce` видно в ответе** (`lastSeenAt` позже); s4b замещённый хост исчезает; s5 удалённое развёртывание исчезает, сервис остаётся; s2b commit/репозиторий из открытого `DEPLOYED_FROM` (данные вставлены Cypher-ом); s6 невалидный/неизвестный gid без эха; s7 401/403 | `F2AcceptanceIT.s1_toolsListContainsFindRuntimeFootprint:247`, `F2AcceptanceIT.s2_footprintOfSeededProd:256`, `F2AcceptanceIT.s3_environmentWithoutDeploymentsKeepsServiceWithEmptyDeployments:292`, `F2AcceptanceIT.s4_deployMapChangeIsVisibleAfterSync:301`, `F2AcceptanceIT.s4b_replacedHostIsNoLongerReturned:339`, `F2AcceptanceIT.s5_deletedDeploymentDisappearsButServiceStays:348`, `F2AcceptanceIT.s2b_footprintReturnsDeploymentSourceCode:361`, `F2AcceptanceIT.s6_invalidAndUnknownGidAreToolErrorsWithoutEchoOrStacktrace:399` (−), `F2AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied:418` (−) |
| `F3AcceptanceIT` (8) | F3 «зависимости и impact», UNLOCKER-188/191, критерии 5 и 7 (`:42`) | s1 схема `trace_dependencies`; s2 trace вниз доходит до VM и `ledger-api` (`DEPENDS_ON`), `HOSTED_ON` не попал; s3 trace вверх `relationTypes=[RUNS_ON]`, `maxDepth=1`; **s4 impact от VM с объяснимыми путями и провенансом/свежестью**; s5 фильтр окружения; s6 шесть невалидных входов (в т. ч. `maxDepth=7`); s7 401/403; s8 холодный impact ≤ 15 с, p95 ≤ 5 с | `F3AcceptanceIT.s1_toolsListContainsTraceDependencies:254`, `F3AcceptanceIT.s2_traceDownstreamFromPaymentsCore:266`, `F3AcceptanceIT.s3_traceUpstreamFromVmLimitedByTypeAndDepth:291` (−), `F3AcceptanceIT.s4_impactFromVmReturnsExplainablePaths:307`, `F3AcceptanceIT.s5_impactEnvironmentFilter:350` (−), `F3AcceptanceIT.s6_invalidInputIsToolErrorWithoutStacktrace:365` (−), `F3AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied:379` (−), `F3AcceptanceIT.s8_latencyWithinBudget:392` |
| `F4AcceptanceIT` (7) | F4 «provenance», UNLOCKER-192/194 (`:43`), зависит от конфликтов E3 | подготовка: `@Primary EventProcessor` с `ScmItSystemMapper`, два актива, связанных `POST /admin/crosswalks`, критичность HIGH (EAM)/LOW (SCM); s1 `tools/list`; s2 EAM `MASTER` + SCM `SUPPLEMENTARY`, `conflictState=OPEN`; s3 фильтр по `property`; s4 tombstone SCM-записи: актив остаётся текущим, конфликт снят; s5 неизвестный gid; s6 401/403; s7 tombstone **мастера** закрывает актив даже при активной записи SCM | `F4AcceptanceIT.s1_toolsListContainsExplainProvenance:319`, `F4AcceptanceIT.s2_assetFromTwoSourcesShowsMasterAndConflict:328`, `F4AcceptanceIT.s3_propertyFilterKeepsOnlyThatPropertyConflict:362` (−), `F4AcceptanceIT.s4_tombstoneInOneSourceKeepsAssetCurrent:375` (−), `F4AcceptanceIT.s5_unknownGidIsToolErrorWithoutStacktrace:405` (−), `F4AcceptanceIT.s6_accessWithoutScopeOrTokenIsDenied:418` (−), `F4AcceptanceIT.s7_tombstoneOfMasterClosesAssetEvenWithActiveSupplementary:432` (−) |

Негатив доступа проверяется для `search_assets` (F1), `find_runtime_footprint` (F2), `trace_dependencies` (F3), `explain_provenance` (F4): без токена 401, с `architecture.other` 403 и `WWW-Authenticate: …insufficient_scope` (`F1AcceptanceIT:350-358`). Отдельной проверки для `get_asset` и `ping` нет 🟡 (все шесть tools заведены в `tool-scopes` конфигурации, `access/mcp-server/src/main/resources/application.yml:28-34`).

#### Z1.3.10 Sync и reconciliation (критерии 2, 3, 4)

`SyncAcceptanceIT` (`@Order` k1…k4, диспетчер включён, четыре адаптера, webhook-отправитель с HMAC; общий ключ HS256) — приёмка синхронизации; `ReconciliationIT` — отдельные свойства reconcile без Spring.

| Поведение | Тесты | Ловушки / критерий |
|---|---|---|
| **Срез загружен** (критерий 1): цепочки `Service→Deployment→Environment`, `RUNS_ON`, `IMPLEMENTED_IN`, `OWNED_BY`, `ITSystem` с целым ключом (5101/5102), `USES_SOLUTION/USES_PLATFORM`, `ASSERTS` минимум от трёх source; `DECOMPOSED_INTO` в т. ч. по `eamCode`; `DEPENDS_ON` ровно один; в журнале нет `QUARANTINED` | `SyncAcceptanceIT.k1_sliceIsLoaded:223`, `SyncAcceptanceIT.k1_decomposedIntoLinksSystemToService:252`, `SyncAcceptanceIT.k1_dependsOnLinksServices:264`, `SyncAcceptanceIT.k1_noQuarantine:271` (−) | 4, 14 |
| **Критерий 2**: 20 изменений по одному через подписанный webhook доходят до графа, p95 ≤ 30 с (замер на реальных часах, печать в `System.out`) | `SyncAcceptanceIT.k2a_webhookLatencyP95:279` | критерий 2 |
| Пропущенные изменения (удаление и добавление без webhook) в граф сами не попадают (пауза 2 с); `POST /admin/reconcile/eam` → 202, пропавшая Team `active=false`/узел `isCurrent=false`, новая появляется, объекты eam-api живы; изменение ITSystem в eam-api подхватывается polling-ом (`ADVANCED`) | `SyncAcceptanceIT.k2b_missedChangesAreFixedByReconcile:313` (−), `SyncAcceptanceIT.k2c_eamApiChangeIsPickedUpByPolling:351` | **9**, критерий 2 |
| **Критерий 3**: повтор того же webhook (202) не создаёт второй строки `inbox_event` и не меняет граф; два webhook в обратном порядке → только актуальная V2, есть `DUPLICATE`, нет карантина; страница polling в перепутанном порядке → актуальная версия (старая не применена: `IGNORED_OLD_VERSION` **или** `DUPLICATE`) | `SyncAcceptanceIT.k3a_repeatedEventChangesNothing:368` (−), `SyncAcceptanceIT.k3b_reversedWebhooks:387` (−), `SyncAcceptanceIT.k3c_reorderedPolling:409` (−) | **6**, критерий 3 |
| **Критерий 4**: `POST /admin/rebuild?confirm=true` → 200 и канонический дамп (`CanonicalGraph.dump`) равен дампу до rebuild, включая tombstone из k2b и версии из k3 | `SyncAcceptanceIT.k4_rebuildGivesEquivalentCanonicalGraph:438` | критерий 4 |
| Удалённое в источнике без webhook обнаруживается **полным snapshot**: пропавший `active=false`, соседний цел (его событие — `DUPLICATE`); пропущенное создание появляется | `ReconciliationIT.missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive:161`, `ReconciliationIT.missedCreateAppearsAfterSnapshot:185` | **9**, критерий 2 |
| **Сбой источника (503) на середине snapshot**: исход `GAVE_UP`, маркера нет, удалённый объект остаётся `active`; рестарт `Poller` продолжает тот же `syncRunId` и завершает snapshot — теперь объект неактивен | `ReconciliationIT.failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun:202` (−) | **9** |
| Объект в карантине не считается пропавшим; повторный `reconcile` того же маркера ничего не tombstone-ит; объект, пришедший webhook-ом в окно snapshot-а, не удаляется этим прогоном (high-water mark) | `ReconciliationIT.quarantinedSnapshotObjectIsNotTreatedAsMissing:233` (−), `ReconciliationIT.reprocessingTheSameMarkerChangesNothing:249` (−), `ReconciliationIT.objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun:268` (−) | **9**, 6 |

#### Z1.3.11 Миграции и схема (сводно)

Нет отдельного класса миграций; поведение распределено: Neo4j-схема — `Neo4jSchemaIT` (Z1.3.3); Flyway PG — `EventJournalIT.migrationsAreIdempotent:237`, `SyncConsoleIT.migrationIsIdempotentForExistingRole:200` (оба без `assert`) и неявно каждый `@BeforeAll`; `TemporalRelationMigration` — `GraphProjectorIT.temporalRelationMigrationFillsMissingValidFromAndIsIdempotent:591`; миграция ключа EAM — `EamKeyMigrationIT` (Z1.3.5). Миграция V1–V7 «на непустой БД предыдущей версии» (upgrade-path) не проверяется ни одним тестом 🟡.

#### Z1.3.12 Метрики

`IngestionMetricsIT.lagAndOpenDlqPerSourceFromJournal:39` — единственный тест метрик: gauge `archrag.ingestion.sync.lag` по источнику = возраст старейшего нетерминального события (`RECEIVED/RETRYING`; `PROJECTED` и `QUARANTINED` не в счёт; у пустых источников и неизвестного `cmdb` = 0), `dlq.open` считает только непереигранные записи (`IngestionMetricsIT:39-61`). Метрик MCP/консоли/адаптеров и эндпоинта `/actuator/prometheus` в IT нет 🟡 (проверяются в `platform/docker`, вне модуля).

#### Z1.3.13 Модульный smoke

`IntegrationTestsSmokeTest.moduleMarkerIsOnClasspath:10` — маркер модуля на classpath (единственный тест, который выполняет surefire).

**Проверка полноты.** из 299 методов `@Test` в тексте названы 299; все.

---

### Z1.4 Матрица «поведение / ловушка `CLAUDE.md` → IT»

Метки: 🟢 покрыто IT (в т. ч. негативным); 🟡 покрыто частично / только unit-тестами модуля / косвенно; 🔴 пробел. Критерии PoC — `docs/00-vision.md:794-807`.

| № | Поведение / ловушка | Где покрыто в IT | Статус |
|---|---|---|---|
| 1 | Cypher только с параметрами; метки/типы из allowlist | `GraphQueryCoreIT.publicApiHasNoMethodAcceptingCypher:137`, `GraphQueryCoreIT.unknownTemplateAndBadParametersFailBeforeDatabase:150`, `SearchAssetsIT.luceneSpecialCharactersDoNotFail:231`, `GetAssetIT.unknownGidAndServiceNodeGidReturnNothing:137`. Негатива «инъекция в `query`/`property` через tool» на уровне MCP нет; ввод `property` вне allowlist — только валидный (`ExplainProvenanceIT`) | 🟡 |
| 2 | Глубина — валидированный `int`; обход без верхней границы запрещён | подстановка числа: `GraphQueryCoreIT.traversalDepthIsBoundedByMaxDepth:214`; отказ `maxDepth=7`: `F3AcceptanceIT.s6_invalidInputIsToolErrorWithoutStacktrace:365`; `depth=3` консоли: `AdminConsoleIT.depthThreeIsRejectedBeforeAnyQuery:265`; запрет `*`/`*1..` — **только unit** (`access/graph-query-core/src/test/…/QueryTemplateRegistryTest`, `DependencyTemplatesTest`); нижняя граница (`maxDepth=0`/отриц.) в IT не проверена | 🟡 |
| 3 | MCP/консоль только читают (`executeRead`, нет write-шаблонов) | `GraphQueryCoreIT.writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed:79`, `GraphQueryCoreIT.readSessionConfigRejectsWritesAtDatabaseLevel:112`, `AdminConsoleIT.everyConsoleTemplatePassesReadOnlyExplainAndWritingTemplateIsClosed:280`, PG: `SyncConsoleIT.consoleRoleCannotWriteOrReadOtherTables:183`. Попытки записи через HTTP MCP нет (write-tool не существует). Раздельность учёток в Community не проверяется (`mcp-reader` — пользователь без ролей) | 🟢 (критерий 6 — на уровне исполнителя) |
| 4 | `MERGE` только по ключу под constraint | `Neo4jSchemaIT.duplicateSourceRecordKeyIsRejectedButOtherSourceTypeAccepted:61`, `GraphProjectorIT.concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion:285`, `GraphProjectorIT.computeInstanceSubtypeChangeKeepsOneNode:683`, `IdentityMappingIT.concurrentResolveConvergesToOneGid:84` | 🟢 |
| 5 | Неполная запись не затирает известные поля | `GraphProjectorIT.partialRecordDoesNotEraseKnownFields:304`, `GraphProjectorIT.partialRecordWithoutHostsClosesNothing:416`, `EamApiProjectionIT.unknownCriticalityIsStoredRawAndDoesNotOverwriteKnownValue:250`; явного `null` в payload и семантики удаления нет (`emptyHosts…KnownLimitation`) | 🟢 (🟡 явный `null`) |
| 6a | Дедупликация по `source + eventId` | журнал: `EventJournalIT.duplicateEventIsNotInsertedTwice:145`, `EventJournalIT.sameEventIdFromAnotherSourceIsAccepted:160`; webhook: `AdapterInboxIT.webhookIsStoredAsReceivedRowWithRawObjectAndDuplicateAddsNothing:99`; сквозь конвейер: `SyncAcceptanceIT.k3a_repeatedEventChangesNothing:368`; replay: `AdminEndpointIT.replayIsIdempotentForSameReplayIdAndRestoresLostProjection:303`. На уровне `EventProcessor` повторная доставка того же `eventId` в `GraphProjectorIT` не вызывается (каждый `handle` берёт новый `eventId`) | 🟢 |
| 6b | `IGNORED_OLD_VERSION` | `GraphProjectorIT:263,277,728,833,846` (`GraphProjectorIT.olderVersionIsIgnoredAndComparedNumerically:256`, `GraphProjectorIT.outOfOrderDeliveryConvergesToTheNewestVersion:269`, `GraphProjectorIT.deleteWithOlderVersionIsIgnored:839`), `EamApiProjectionIT.repeatedVerIsNoOpAndOlderVerIsIgnored:233`; в сквозном `SyncAcceptanceIT.k3c_reorderedPolling:409` принимается `IGNORED_OLD_VERSION` **или** `DUPLICATE` (`SyncAcceptanceIT:429`) | 🟢 |
| 6c | Checkpoint сдвигается только после коммита Neo4j | `GraphProjectorIT.graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint:875` (при сбое не сдвинут, после повтора сдвинут); `GraphProjectorIT.upsertCreatesNodeWithProvenanceAndMovesEventToProjected:199`. High-water mark: `ReconciliationIT.objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun:268`; для адаптерного checkpoint — только косвенно (`ReconciliationIT:202`) | 🟢 (🟡 checkpoint адаптера) |
| 7 | Нет побочных эффектов в transaction functions (повтор при транзиентных ошибках) | ни один тест не имитирует транзиентный сбой внутри `executeRead/executeWrite` и не проверяет отсутствие побочных эффектов в PG/S3 | 🔴 |
| 8 | Сессии и драйвер закрываются, один драйвер на приложение | прод-поведение в IT не проверяется; **сами тесты** не закрывают драйверы в большинстве классов `M` (`Neo4jSchemaIT:28`, `IdentityCandidateIT`, `ReconciliationIT`, `GetAssetIT`, `SearchAssetsIT`, `TraceDependenciesIT` и др.); закрывают `ExplainProvenanceIT:133-138`, `AdminConsoleIT:89-101` | 🔴 (тест-код) |
| 9a | Удаление только после полного snapshot (позитив) | `ReconciliationIT.missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive:161`, `JournalDispatcherIT.snapshotMarkerTombstonesObjectMissingFromRun:172`, `AdminEndpointIT.reconcileTombstoneSurvivesRebuild:413`, `SyncAcceptanceIT.k2b_missedChangesAreFixedByReconcile:313`, `EamKeyMigrationIT.m3_reconcileTombstonesOldKeysAndKeepsTeamAndDependencies:207`, `F4AcceptanceIT.s7_tombstoneOfMasterClosesAssetEvenWithActiveSupplementary:432` | 🟢 |
| 9b | …на середине загрузки удаление запрещено (негатив) | `ReconciliationIT.failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun:202`, `JournalDispatcherIT.markerClaimingMoreObjectsThanJournalHoldsDeletesNothing:194`, `ReconciliationIT.quarantinedSnapshotObjectIsNotTreatedAsMissing:233`, `AdapterInboxIT.snapshotEndsWithMarkerOnRealInbox:135` (маркер последним) | 🟢 |
| 10 | Время — `Instant`/UTC, в Neo4j `datetime` | `GraphProjectorIT.temporalRelationGetsValidFromAndIsNotReopenedByRepeat:343`, `DeployedFromIT.newCommitClosesOldDeployedFromAndOpensNewOne:294` (`asZonedDateTime().toInstant()`), парсинг `Instant.parse` в ответах MCP. Запрета `LocalDateTime` тестом нет (проверяется только отсутствием) | 🟡 |
| 11 | Тексты источников недоверенные, не логируются целиком | `JournalDispatcherIT.brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected:137` (причина без payload), tool-ошибки без эха значения (`F1AcceptanceIT.s7_unknownGidIsToolErrorWithoutStacktrace:337`, `F2AcceptanceIT.s6_invalidAndUnknownGidAreToolErrorsWithoutEchoOrStacktrace:399`, `ExplainProvenanceIT.unknownGidIsAssetNotFound:269`), консоль: `SyncConsoleIT.eventMetadataHasRefAndHashButNoPayload:289`, `SyncConsoleIT.oversizedAuditResultIsNotReturned:240`. «Текст не исполняется как инструкция» тестом не проверяется | 🟡 |
| 12 | Секреты/токены не в логах и исключениях | явного теста нет; `JournalDispatcherIT.brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected:137` проверяет лишь отсутствие payload в причине; пароль Neo4j/PG в `toString` — unit-тесты модулей | 🟡 |
| 13a | MCP: 401 без токена, 403 при неверном scope | `F1AcceptanceIT.s8_accessWithoutScopeOrTokenIsDenied:350` (`search_assets`), `F2AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied:418`, `F3AcceptanceIT.s7_accessWithoutScopeOrTokenIsDenied:379`, `F4AcceptanceIT.s6_accessWithoutScopeOrTokenIsDenied:418`. Нет: неверный audience/issuer, просроченный и подделанный токен, `get_asset`/`ping` отдельно | 🟡 → 🔴 по audience/exp |
| 13b | Admin REST ingestion | `rebuild/replay`: 401/403, чужой audience, чужой `azp` — `AdminEndpointIT.tokenForForeignAudienceIsUnauthorized:375`, `AdminEndpointIT.tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope:383`, `AdminEndpointIT.scopeIsEnforced:393`; `reconcile`: 404/502/403/401; `crosswalks`: **только 403** (`AdminEndpointIT:398`), без 401, audience, azp | 🟡 |
| 13c | Консоль | 401 без токена и 403 по scope только для `/api/sync/events`, `/api/audit` (`SyncConsoleIT.withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized:212`). Для `/api/graph/*` (`AdminConsoleIT:158-168` всегда шлёт валидный токен) и `/api/identity/*`, `/api/sync/dlq|sources` — ничего; **неверные audience и `azp` не проверяются нигде** | 🔴 |
| 14 | Ошибки не глушатся: `QUARANTINED`/`RETRYING` с кодом | `JournalDispatcherIT.unsupportedTypeIsQuarantinedWithCode:160`, `JournalDispatcherIT.markerWithoutRawGoesToDlq:219`, `GraphProjectorIT.invalidPayloadIsQuarantinedWithCodeAndWritesNothing:916`, `GraphProjectorIT.graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint:875`, `DeployedFromIT.invalidCommitIsQuarantinedAndGraphIsUntouched:360`, `EventJournalIT.transitionsAndDlqArePersisted:170`; ошибки tool/REST без стектрейса — F1–F4 `s6/s7`; `SOURCE_CONFLICT_FAILED` в IT нет (только `EventProcessorTest`) | 🟢 |
| — | Tombstone вместо hard delete (ADR 0020): узел не удаляется, закрывается | `GraphProjectorIT.tombstoneDeactivatesRecordArchivesNodeAndClosesRelations:773`, `GraphProjectorIT.tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains:810`, `EamKeyMigrationIT.m4_rebuildHasNoItSystemWithOldKeysAndNoDuplicates:225` (старые узлы в архиве). Отдельного «узел физически существует после tombstone» для snapshot-пути нет | 🟢 |
| — | Authority matrix (ADR 0008), конфликты (ADR 0030/0031) | `GraphProjectorIT.nonAuthoritativeSourceDoesNotReplaceCanonicalValue:320`, `SourceConflictIT.nonAuthoritativeValueOpensConflictAndMarksOnlyDissentEdge:199`, `PlatformInvariantIT` (7), `DeployedFromIT` (5), `PostgresSourceConflictsIT` (12) | 🟢 |
| — | Критерий 1 (срез) / 2 (p95 30 с; polling = интервал + 5 с) / 3 (повтор, out-of-order) / 4 (rebuild) | `SyncAcceptanceIT.k1_sliceIsLoaded:223` / `SyncAcceptanceIT.k2a_webhookLatencyP95:279` (polling-границы «интервал + 5 с» нет, только `k2c` без замера) / `SyncAcceptanceIT.k3b_reversedWebhooks:387` + `GraphProjectorIT.outOfOrderDeliveryConvergesToTheNewestVersion:269` / `SyncAcceptanceIT.k4_rebuildGivesEquivalentCanonicalGraph:438`, `AdminEndpointIT.rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt:256` | 🟢 (критерий 2: 🟡 polling) |
| — | Критерий 5 (пять tools по Streamable HTTP) / 6 / 7 | `F1–F4` (пять tools: `search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies`, `explain_provenance`); протокол в IT и в `mcp-server` — `STATELESS` (см. Z1.6.3); критерий 6 — на уровне исполнителя (см. №3); критерий 7 — `F3AcceptanceIT.s4_impactFromVmReturnsExplainablePaths:307`, `TraceDependenciesIT.stoppingVmReachesServicesAndSystemsWithStepProvenance:259` | 🟢 (🟡 протокол) |
| — | Критерий 8 (backup/restore Neo4j) / 9 (p95, lag, DLQ rate, глубина) | backup/restore в IT отсутствует (`platform/docker`, вне модуля); 9: `F3AcceptanceIT.s8_latencyWithinBudget:392`, `RuntimeFootprintIT.coldFirstCallFitsProductionTimeout:268`, `IngestionMetricsIT.lagAndOpenDlqPerSourceFromJournal:39`, `TraceDependenciesIT.denseGraphImpactIsTruncatedWithinTimeout:375` | 🟡 |

---

### Z1.5 Вспомогательные классы

| Класс | Назначение и контракт | Кто использует |
|---|---|---|
| `TestImages` (`TestImages:9`) | единственная константа `NEO4J = DockerImageName.parse("neo4j:5.26.31-community")`; комментарий: плавающий тег может сломать CI | 26 классов |
| `McpTestJwt` (`McpTestJwt:31-91`) | RSA-2048 в памяти (`:40-42`), JWKS на `HttpServer` по `getLoopbackAddress():0` (`:43-52`), `jwkSetUri()` возвращает `http://localhost:<port>/jwks` (`:59`); `RESOURCE = "https://mcp.test/mcp"` (`:33`) = и issuer, и audience по умолчанию; `token(scopes…)` (`:64`) → `tokenFor("test-user", RESOURCE, …)` (`:69`) → `tokenForClient("archrag-demo", …)` (`:74`); claims: `iss`, `sub`, `aud`, `azp`, `iat = now−60 s`, `exp = now+5 min`, `scope = "<s1> <s2>"` (`:76-79`), подпись RS256 (`:80`); `close()` останавливает сервер (`:88`). Копия `TestJwt` из `mcp-server` (его test-sources недоступны) | `AdminConsoleIT`, `SyncConsoleIT`, `AdminEndpointIT`, `F1–F4` |
| `AssetFixtures` (`AssetFixtures:30-95`) | засев графа **через настоящий `GraphProjector`**: `node(...)` (мастер по `AuthorityMatrix.defaults()`), `nodeFrom(source, …)` (дополнительная запись другого источника), `tombstone(key, gid, at)`, `relation(type, from, to, validity, at)`; версия `"1"`, `contentHash = "sha256:<sourceId>"`; исход обязан быть `APPLIED`, иначе `IllegalStateException` (`:89-94`). Формы `lastSeenAt/isCurrent/active/ASSERTS.authority` совпадают с продовыми | `GetAssetIT`, `SearchAssetsIT`, `TraceDependenciesIT`, `AdminConsoleIT` |
| `CanonicalGraph` (`CanonicalGraph:17-62`) | канонический дамп для сравнения «до/после»: узлы 7 канонических меток + `SourceRecord` + связи из фиксированного списка 10 типов (включая `ASSERTS`); отсортированные строки; список исключаемых полей **пуст** (`:23`, сравнение не ослаблять без решения архитектора); `SyncRun`/`PROCESSED` не входят | только `SyncAcceptanceIT` (k2b, k3a, k4); `AdminEndpointIT` использует собственный `dump()` 🟡 |
| `EamApiSupport` (`EamApiSupport:17-38`) | `EamApiStub` + `EamApiSeed.seeded(clock)` с токеном `it-eam-api-token`; `adapter(legacyUri, secret, journal, rawStore)` собирает `EamAdapter` с eam-api **и** старой заглушкой (TEAM, SERVICE_DEPENDENCY) — EAM после перехода на ключ `id` | `AdminEndpointIT`, `EamKeyMigrationIT`, `F1–F4`, `SyncAcceptanceIT` |
| `ScmItSystemMapper` (`ScmItSystemMapper:16-45`) | тестовый `CanonicalMapper` для SCM: `IT_SYSTEM` → `UpsertNode(ITSystem(name, criticality))`, остальные типы делегируются `ScmMapper`; в PoC SCM `IT_SYSTEM` не присылает, а для сценариев provenance нужен актив с записями двух источников | `ExplainProvenanceIT`, `F4AcceptanceIT` (через `@Primary EventProcessor`) |

---

### Z1.6 Сомнения и расхождения

Нумерация внутри раздела; приоритет — по убыванию.

#### Z1.6.1 🔴 Асимметрия негативных проверок безопасности

- Консоль: 401/403 проверены только на `/api/sync/events` и `/api/audit` (`SyncConsoleIT:211-218`; 401 для `/api/audit` нет). `AdminConsoleIT` всегда отправляет валидный admin-токен (`AdminConsoleIT:158-168`) — для `/api/graph/*` и `/api/identity/*` отказов нет. Неверный audience и `azp` не проверяются **нигде** (в консоли `azp` не проверяется и в коде: `AuthorizedPartyValidator` есть только в ingestion, `ingestion/ingestion-service/…/AuthorizedPartyValidator.java:27`) — это подтверждает X5.4/X3.5 и расширяет их 🟡/🔴.
- `/admin/crosswalks`: только 403 по scope (`AdminEndpointIT:398`), без 401, audience и `azp`; `/admin/reconcile/*`: без audience/`azp`. Для `rebuild`/`replay` покрыто полностью (`AdminEndpointIT:375-401`).
- MCP: нет теста на неверный audience/issuer, просроченный (`McpTestJwt` умеет `tokenFor(sub, aud)`/`tokenForClient`, но срок жизни фиксирован +5 мин, `McpTestJwt:78`) и подделанный токен; `get_asset` и `ping` отдельно не проверяются.
- Реальная цепочка JWT ingestion работает только в `AdminEndpointIT`; в `F1–F4`/`SyncAcceptanceIT`/`EamKeyMigrationIT`/`JournalDispatcherIT` она заменена HS256-декодером без `aud`/`azp` (Z1.2.6) 🟡.

#### Z1.6.2 🟡 Зависимость от порядка и общего состояния

- `@TestMethodOrder(OrderAnnotation)`: `EamKeyMigrationIT` (5 методов `@Order(1..5)`, `adapters.getLast()` в m2/m3 требует предшественников), `SyncAcceptanceIT` (11, `@Order(1..11)`; Javadoc заявляет «каждый фиксирует своё исходное состояние», но k2c опирается на сервис из k2a, k4 — на tombstone из k2b и версии из k3, `SyncAcceptanceIT:73-74,360-361,447`), `F1–F4`. Одиночный запуск `-Dit.test=Class#method` для них недопустим (F3 s8 требует `coldImpact` из s4, `F3AcceptanceIT:396`).
- **Два метода с `@Order(2)`** в `F2AcceptanceIT` (`:255` s2 и `:360` s2b); s2b физически стоит после s5, но по `@Order` равен s2 — порядок между ними неявен 🔴 хрупкость. Цепочка мутаций F2: s4 (смена хоста) → s4b → s5 (удаление), s3 зависит от неизменённого состояния.
- `AdminEndpointIT`: общий ленивый `seed()` (`static boolean seeded`, `:116,134-136`) и мутирующие тесты (`:325` — `DETACH DELETE` всего графа; `:413-432` — добавление `TEAM-GONE`); при падении теста посередине последующие получат пустой граф. Пустой `@BeforeAll noop()` с Javadoc, описывающим `seed()` (`:119-121`) — мусор 🟡.
- Классы без очистки (`ExplainProvenanceIT`, `SourceConflictIT`, `EamApiProjectionIT` в PG, `DeployedFromIT` в PG) полагаются на уникальные `UUID`/ключи; фиксированные ключи `5101`, `5104`, `7201` переиспользуются между тестами при том, что identity в PG не очищается 🟡.
- `JournalDispatcherIT`: диспетчер работает в фоне весь класс; «зависший» маркер из `markerClaimingMoreObjects…` вечно ретраится (retry-delay 1 с) и остаётся в журнале до конца класса; граф и `inbox_event` не чистятся (`:113-119`) 🟡.

#### Z1.6.3 🟡 Расхождения с `CLAUDE.md` / docs (решения записаны в ADR)

- **Протокол MCP.** `CLAUDE.md` требует `spring.ai.mcp.server.protocol=STREAMABLE`; IT поднимают MCP с `STATELESS` (`F1AcceptanceIT:201`), как и `access/mcp-server/src/main/resources/application.yml:11`. Это зафиксировано ADR 0017 (`docs/adr/0017-mcp-streamable-http.md:14,34` — «уточнено 2026-10-07 (UNLOCKER-224): в коде `STATELESS`»). Код и тесты следуют ADR, устарел текст профиля. Клиент в тестах — `HttpClientStreamableHttpTransport`, т. е. wire-формат Streamable HTTP проверяется, stateful-сессии — нет.
- **S3 в тестах: SeaweedFS, не MinIO** — ADR 0007 (см. Z1.2.1).
- **Тег PostgreSQL** `postgres:16` — не точный (Z1.2.1).
- **Правило «tombstone мастера закрывает актив при активной supplementary-записи»** (`F4AcceptanceIT.s7`, комментарий «Фиксирует правило проектора», `F4AcceptanceIT:432-433`; поддержано `GraphProjectorIT.tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains`). ADR 0020 формулирует шире: «Канонический узел закрывается только при отсутствии **других активных assertions**» (`docs/adr/0020-tombstone-instead-of-hard-delete.md:14`); IT и код закрывают узел по отсутствию активных **MASTER**-assertions. Формулировку ADR стоит уточнить 🟡.
- **Ссылка в ADR 0031 на несуществующий файл**: `0020-no-physical-delete.md` (`docs/adr/0031-it-system-platform-invariant.md:3`), тогда как файл называется `0020-tombstone-instead-of-hard-delete.md` 🟡 (докладная опечатка, к IT не относится).
- **ADR 0031 и `PlatformInvariantIT` (7 тестов)**: не покрыты IT пункты ADR «нет `platformId`/решение неизвестно → строка закрывается», нецелое `platform_id` (карантин), сбой хранилища конфликтов (`RETRYING`), `IGNORED_OLD_VERSION`/tombstone решения не запускают перепроверку, маркеры `ASSERTS.conflicts` у системы. Часть покрыта unit-тестами (`ingestion/graph-projector/src/test/…/EventProcessorTest.java:898-912` — сравнение платформ, `:193,230,820` — `SOURCE_CONFLICT_FAILED`/`RETRYING`); остальное не проверено 🟡.

#### Z1.6.4 🟡 Имитации там, где имя обещает реальный путь

- `SourceConflictIT.reconciliationTombstoneResolvesConflictAndClearsMarkers`: маркер snapshot подменён событием типа `TYPE_ASSET_UPSERTED` (`SourceConflictIT:295-299`), а не `architecture.sync.snapshot-complete.v1`; `filler` только добавляется в журнал и не обрабатывается `EventProcessor`. `rebuildKeepsNodeConflictAndMarker` — «rebuild» это `DETACH DELETE` + переигровка с новыми `eventId`, а не `RebuildService` (`SourceConflictIT:322-338`).
- `ExplainProvenanceIT.tombstonedSourceIsInactiveAndConflictIsCleared`: комментарий «маркер уже обработан», на деле маркер лишь добавляется в журнал и передаётся `reconciler.reconcile` напрямую (`ExplainProvenanceIT:241-255`) — проверяется `Reconciler`, а не путь «маркер → триггер». Tool вызывается как Java-метод: scope, `ToolScopeFilter` и 401/403 здесь не участвуют.
- `GraphProjectorIT.missingServiceDependencyIsFoundByReconcilerAndItsEdgeIsClosed` — `Reconciler` не вызывается, tombstone шлётся вручную (🟡 имя шире содержания).
- `ScmItSystemMapper` и `CmdbSystemMapper` (в `SourceConflictIT`) воспроизводят то, чего нет в проде (SCM/CMDB `IT_SYSTEM` не присылают) — сценарий искусственный.

#### Z1.6.5 🟡 Имена, обещающие больше, чем проверяет тело

- `SearchAssetsIT.fulltextFindsBySearchableTextAndExactStillWins` — exact-ветка в теле не проверяется; `luceneSpecialCharactersDoNotFail` проверяет только отсутствие исключения.
- `EventJournalIT.migrationsAreIdempotent` и `SyncConsoleIT.migrationIsIdempotentForExistingRole` — без `assert` (`EventJournalIT:237-240`).
- `EamApiProjectionIT.repeatedVerIsNoOpAndOlderVerIsIgnored` — для повтора `isNotEqualTo(QUARANTINED)` (`EamApiProjectionIT:235`), ожидаемый статус не зафиксирован.
- `IdentityCandidateIT.replayKeepsOneCandidateAndFirstSeenAt` — «replay» это версия 2 с новыми `eventId`, не повторная доставка тех же событий.
- `TraceDependenciesIT.dense…WithinTimeout` — время явно не измеряется (падает исключением таймаута, а не `assert`).
- `AdapterInboxIT.webhookIs…` — `assertThat(key).isNotNull()` (`AdapterInboxIT:109`), смысл несёт чтение объекта из S3.

#### Z1.6.6 🟡 Временные утверждения и флейки

- `F3AcceptanceIT.s8`: **p95 по 10 замерам равен максимуму** (`percentile` = `ceil(0.95·n)−1`, `F3AcceptanceIT:497-500`); порог 5 с на графе из ≈ десятка узлов заведомо свободный; замер включает создание MCP-клиента и выпуск токена на каждый вызов; «холодный» вызов — не первый в классе (s1–s3 уже прогрели JVM/пул/JWKS).
- `SyncAcceptanceIT.k2a`: p95 по 20 замерам на реальном времени CI, печать в `System.out`; порог 30 с, ожидание 60 с; `Thread.sleep(2000)` в k2b — «ничего не должно измениться» (слабая гарантия).
- `RuntimeFootprintIT.coldFirstCallFitsProductionTimeout` — порог 3 с; «холодность» не гарантирована (порядок методов не зафиксирован), `GraphQueryCoreIT.timeoutGivesQueryTimeoutException` — граница 20 с.
- `IngestionMetricsIT:55` — `isBetween(119 d, 180 d)` (допуск на время выполнения); `SyncConsoleIT:334` — `lagSeconds isPositive()` верно, пока `now()` позже сида 2026-01.
- `EventJournalIT:289` — окно `receivedAt..+1000 нс` опирается на микросекундную точность `timestamptz`.
- `McpTestJwt`: JWKS слушает `getLoopbackAddress()`, а `jwkSetUri()` отдаёт `localhost` (`McpTestJwt:45,59`) — на системе, где `localhost` → IPv6 при IPv4-loopback (или наоборот), получение ключей может не сработать 🟡.

#### Z1.6.7 🟡 Прочее

- **Каркас F1–F4 дублируется** (≈ 200 строк в четырёх файлах: `post`, `awaitTrue`, `count`, `scalarLong`, `startMcp`, `awaitIdle`) — риск расхождения; общего базового класса нет.
- `SyncConsoleIT:374` фиксирует, что `/api/audit` отдаёт `request` (jsonb) как есть, включая ключ `secret` из сида. Это фикстура со значением `x`, а не нарушение ловушки 12; но если `admin_audit.request` когда-либо получит секреты, консоль их вернёт — маскирования нет (🟡, ср. X3.5).
- Cypher/SQL в тестах местами собирается конкатенацией (`GraphProjectorIT.node()`/`openCount()`, `RuntimeFootprintIT.deployment()`, `SyncAcceptanceIT:301,359-361`, `F4AcceptanceIT:249`, `SyncConsoleIT:60,107-111`): только из констант теста, но стиль расходится с ловушкой 1 и не должен копироваться в прод-тесты.
- Тест `F2AcceptanceIT.s4b_replacedHostIsNoLongerReturned` помечен в Javadoc как «Дефект E1»; тесты не запускались, поэтому неизвестно, исправлен ли дефект (`F2AcceptanceIT:336-337`) 🟡.
- `F2AcceptanceIT.s2b` пишет в Neo4j напрямую (SET `sourceCommit`, два ребра `DEPLOYED_FROM`) в обход `GraphProjector`; `stale=true` в F2 нигде не проверяется (только `false`) 🔴 пробел покрытия свежести.
- Тест не проверяет `Dockerfile`/compose-стенд и backup/restore Neo4j (критерий 8) — это `platform/docker` (вне модуля).
- `pom.xml` модуля подключает `admin-console` и `mcp-server` одновременно: у обоих `application.yml`, какой попадёт в classpath-контекст ingestion, зависит от порядка зависимостей (`pom.xml:92`), поэтому все свойства соседних приложений передаются аргументами 🟡.

---

### Z1.7 Итоговая строка (для таблицы итогов)

| Модуль | Файлов test / строк | Тестов | Ключевые механизмы | Основные риски (см. §) |
|---|---|---|---|---|
| `platform/integration-tests` | 38 (31 `*IT` + 1 unit + 6 хелперов) / ≈ 10 880; `src/main` — 1 маркер | 299 `@Test` (298 в `*IT`) в 32 классах, `@ParameterizedTest` 0 | Testcontainers: Neo4j `5.26.31-community` + `postgres:16` + SeaweedFS `4.48`, контейнеры на класс без reuse (≈ 60 запусков); 4 способа запуска (M/SB/SA/bean); `McpTestJwt` (RSA + JWKS) против HS256-заглушки; сквозные цепочки F1–F4/Sync на `@Order` | Z1.6.1: асимметрия негативов безопасности; Z1.6.2: порядок и общее состояние (`@Order`, дубль `@Order(2)` в F2); Z1.6.4–5: имитации и «обещающие» имена; Z1.6.6: временные утверждения (p95 при n=10) |


---

## W1. `platform/docker` (не Maven-модуль)

Сокращение: `PD` = `platform/docker`. Метки: 🟢 подтверждено кодом/конфигом · 🟡 выведено · 🔴 пробел/расхождение. Дизайн-контекст не дублируется: ADR [0021](../docs/adr/0021-compose-demo-stand.md) (Compose вместо Kubernetes, backup через `dump/load`), [0026](../docs/adr/0026-admin-console-readonly-loopback.md) (консоль, loopback, `azp`), [0007](../docs/adr/0007-s3-raw-storage-seaweedfs.md) (SeaweedFS вместо MinIO), перечень отличий от production — `docs/05-production-gap-list.md` (§1.7, 2.1, 2.4, 2.7). Правка файлов репозитория не выполнялась, `docker`/`mvn` не запускались: утверждения о рантайме выведены из конфигурации (🟡), кроме отмеченных локальным экспериментом.

**Назначение.** Демо-стенд PoC (E5.1–E5.5) одной командой `docker compose up`: инфраструктура (Neo4j, PostgreSQL, SeaweedFS, Keycloak), приложения (ingestion, mcp-server, admin-console, четыре адаптера), заглушки мастер-систем, единственный TLS-вход (nginx), необязательная наблюдаемость и набор bash-скриптов приёмки (smoke, backup/restore, замеры). Каталог не входит в Maven-реактор: образы собираются из jar, которые уже собрал `./mvnw package` (`PD/Dockerfile:1`, `PD/README.md:11`). 🟢

### W1.1 Состав

| Файл | Строк | Роль |
|---|---|---|
| `PD/docker-compose.yml` | 574 | 23 сервиса (20 в профиле по умолчанию + 3 профиля `observability`), 6 сетей, 9 secrets, 8 томов; проект `name: archrag` (`:4`) |
| `PD/Dockerfile`, `PD/entrypoint.sh`, `PD/Dockerfile.dockerignore` | 14 / 11 / 4 | один образ-обёртка для всех Java-приложений: `eclipse-temurin:21-jre`, непривилегированный uid 10001 (`Dockerfile:3,6,10`); контекст сборки — корень репо, в образ попадают только `*-exec.jar` и `entrypoint.sh` (`Dockerfile.dockerignore:2-4`) |
| `PD/.env.example` | 54 | демо-значения всех переменных стенда; реальный `.env` в `.gitignore` (`.gitignore:31`) |
| `PD/README.md` | 244 | инструкция стенда (запуск, сети и секреты, консоль, наблюдаемость, backup/restore, замеры, допущения) |
| `PD/keycloak/archrag-realm.json` | 113 | realm `archrag`: 2 клиента, 2 client scope, 1 роль, 1 пользователь; значения секретов — плейсхолдеры `${VAR}` |
| `PD/neo4j/init-reader.sh`, `PD/postgres/init-console-ro.sh` | 22 / 11 | одноразовые init: пользователь Neo4j `archrag_reader`, пароль роли PostgreSQL `archrag_console_ro` |
| `PD/proxy/nginx.conf` | 63 | TLS-терминация и маршрутизация `/mcp`, PRM, OIDC-endpoint'ы Keycloak, 4 вебхука |
| `PD/certs/gen-certs.sh` | 41 | демо-CA и серверные сертификаты (proxy, Bolt Neo4j) |
| `PD/observability/*` | 28 / 8 / 8 / 8 / 153 | `otel-collector.yml`, `prometheus.yml`, provisioning Grafana (datasource, provider), дашборд `archrag-poc.json` (4 панели) |
| `PD/smoke.sh`, `PD/console-smoke.sh` | 89 / 126 | приёмка внешней границы (TLS, 401/200, сети, секреты) и консоли (PKCE, 401/403/200) |
| `PD/scripts/{lib,backup,restore,graph-fingerprint,backup-restore-check,deployment-commit-check,measure}.sh` | 119 / 48 / 54 / 56 / 53 / 133 / 381 | backup/restore Neo4j, отпечаток графа, сценарий смены commit, замеры PoC |

Образы приложений: `archrag/{ingestion,mcp-server,admin-console,demo-launcher}:dev` — четыре образа из одного Dockerfile; `demo-launcher` запускается в 9 ролях (4 адаптера, 4 заглушки, `eam-api-stub`) аргументами `command` (`PD/docker-compose.yml:6-12,312-313,382-383,435-436`). 🟢

### W1.2 Карта сервисов compose

Сети: `E`=edge, `B`=backend (internal), `G`=graph (internal), `O`=observability (internal), `OE`=obs-edge, `CE`=console-edge (`PD/docker-compose.yml:528-543`). Секреты — имена Docker secrets (`:546-564`). «Ждёт» = `depends_on` с условием `healthy`, если не указано иное. Подробности по переменным и портам — `data-dictionary.md` §W.1.

| Сервис (стр.) | Образ / тег | Порты на хост | Сети | Ждёт | Healthcheck | Тома | Секреты |
|---|---|---|---|---|---|---|---|
| `certs` (41-49) одноразовый | `alpine/openssl:3.5.4` | — | B | — | — (`restart: "no"`) | `certs`, `ca-key`, `gen-certs.sh:ro` | — |
| `neo4j` (51-85) | `neo4j:5-community` 🔴 плавающий | — (7474/7687 не публикуются) | G | `certs` completed | `cypher-shell … RETURN 1` по `bolt+ssc://localhost:7687`, 10 с/30 с/12 попыток/start 20 с (`:79-85`) | `neo4j-data`, `backups`, `certs:ro` | `neo4j_password` |
| `neo4j-init` (89-100) одноразовый | `neo4j:5-community` | — | G | `neo4j` | — | `init-reader.sh:ro` | `neo4j_password`, `neo4j_reader_password` |
| `postgres` (102-117) | `postgres:16` 🟡 плавающая мажорная | — | B | — | `pg_isready`, 3 с/3 с/20 | `pg-data` | `pg_password` (`POSTGRES_PASSWORD_FILE`) |
| `seaweedfs` (119-135) | `chrislusf/seaweedfs:4.48` | — | B | — | `wget` к `:8333`, любой HTTP 1xx–4xx, 5 с/30 | `s3-data` | нет (ключи в `environment`) |
| `keycloak` (137-175) | `quay.io/keycloak/keycloak:26.4`, `start-dev --import-realm` | `127.0.0.1:${ARCHRAG_KEYCLOAK_PORT:-8080}:8080` | E, B | — | `/dev/tcp` к mgmt-порту 9000 `/health/ready`, start 60 с, 60 попыток | realm-файл `:ro` (тома данных нет) | нет (пароли в `environment`) |
| `ingestion` (178-220) | `archrag/ingestion:dev` | — | B, G, O | `neo4j`, `postgres`, `seaweedfs` | `curl -s http://127.0.0.1:8081/` (любой HTTP-ответ = готов), 30 попыток | — | `pg_password`, `neo4j_password`, `s3_access_key`, `s3_secret_key` |
| `mcp-server` (222-252) | `archrag/mcp-server:dev` | — (масштабируется `--scale`) | E, G, O | `neo4j`, `neo4j-init` completed, `keycloak`, `ingestion` | `curl -fs :8080/actuator/health` | — | только `neo4j_reader_password` |
| `console-pg-init` (256-270) одноразовый | `postgres:16` | — | B | `ingestion` (роль создаёт миграция V6) | — | `init-console-ro.sh:ro` | `pg_password`, `console_pg_password` |
| `admin-console` (274-305) | `archrag/admin-console:dev` | `127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}:8080` | B, G, CE | `neo4j`, `neo4j-init`, `console-pg-init` completed, `keycloak`, `ingestion` | `curl -fs :8080/actuator/health` | — | `neo4j_reader_password`, `console_pg_password` |
| `adapter-eam` (311-336) | `archrag/demo-launcher:dev`, `adapter EAM` | — | E, B | `postgres`, `seaweedfs`, `ingestion`, `stub-eam`, `eam-api-stub` | `curl -fs :8090/health` (якорь `adapter-health`) | — | `pg_password`, `s3_*` ×2, `webhook_secret`, `eam_api_token` |
| `adapter-scm` / `-cmdb` / `-deploymap` (338-378) | то же, `adapter SCM` / `CMDB` / `DEPLOY_MAP` | — | E, B | `postgres`, `seaweedfs`, `ingestion`, своя заглушка | как у `adapter-eam` | — | `pg_password`, `s3_*` ×2, `webhook_secret` (якорь `:22-26`) |
| `stub-eam` / `-scm` / `-cmdb` / `-deploymap` (381-428) | `archrag/demo-launcher:dev`, `stub <СИСТЕМА>` | — | E | — | `curl -fs :8080/changes?limit=1` (якорь `stub-health`) | — | `webhook_secret` |
| `eam-api-stub` (434-445) | `archrag/demo-launcher:dev`, `eam-api-stub` | — | E | — | `curl` с `Authorization: Token <secret>` к `/api/platforms/` | — | `eam_api_token` |
| `reverse-proxy` (448-468) | `nginx:1.27-alpine` | `127.0.0.1:${ARCHRAG_PUBLIC_PORT:-8443}:8443` | E | `certs` completed, `mcp-server`, `keycloak`, 4 адаптера | `wget --no-check-certificate https://127.0.0.1:8443/healthz` | `nginx.conf:ro`, `certs:ro` | — |
| `otel-collector` (473-481) профиль | `otel/opentelemetry-collector-contrib:0.140.1` | — | O | — | нет (в образе нет shell, `:480-481`) | `otel-collector.yml:ro` | — |
| `prometheus` (483-499) профиль | `prom/prometheus:v3.7.3`, retention 2 дня | — | O | `otel-collector` **started** | `wget` к `/api/v1/targets`, цель `up` | `prometheus-data`, `prometheus.yml:ro` | — |
| `grafana` (501-526) профиль | `grafana/grafana:12.2.1` | `127.0.0.1:${GRAFANA_PORT:-3000}:3000` | O, OE | `prometheus` | `wget /api/health` | `grafana-data`, provisioning и дашборды `:ro` | `grafana_admin_password` (`GF_SECURITY_ADMIN_PASSWORD__FILE`) |

Итого: **23 сервиса**, из них 3 одноразовых (`certs`, `neo4j-init`, `console-pg-init`), 3 за профилем `observability` (`:474,484,502`), на хост публикуются 4 порта, все на `127.0.0.1`. 🟢

**Сети и границы** 🟢 (`PD/docker-compose.yml:528-543`): `edge` — единственная не-internal сеть приложений (прокси, MCP, Keycloak, адаптеры, заглушки; из неё возможен исходящий выход наружу); `backend` (PostgreSQL, SeaweedFS, `certs`, ingestion, admin-console, адаптеры, Keycloak), `graph` (Neo4j, ingestion, mcp-server, admin-console, init), `observability` — все `internal: true`; `obs-edge` и `console-edge` не internal и нужны только затем, чтобы публикация порта на loopback вообще работала (комментарии `:517,290`). Сеть `default` не используется (`:2`). `mcp-server` не в `backend` (не видит PostgreSQL/S3), `ingestion` не в `edge` (админка недоступна с прокси) — обе границы проверяются `smoke.sh:42-57`.

**YAML-якоря** 🟢: `x-demo-launcher` (сборка + образ, `:6-12`), `x-adapter-env` (`ARCHRAG_PG_URL/USER`, `ARCHRAG_S3_ENDPOINT/BUCKET`, `:16-20`), `x-adapter-secrets` (`:22-26`), `x-adapter-networks`, `x-adapter-depends`, `adapter-health`, `stub-health`. `adapter-eam` не использует `x-adapter-secrets` и перечисляет секреты сам (+`eam_api_token`, `:314-320`). Адаптеры жёстко `deploy.replicas: 1`: замок snapshot в `Poller` живёт внутри процесса (`:307-309,330`).

### W1.3 Цепочка запуска

1. **Сборка**: `./mvnw package -DskipTests` → jar с классификатором `exec` в `*/target/` (`PD/README.md:11`; пути зашиты в `build.args.JAR`, `PD/docker-compose.yml:11,183,227,279`). `docker compose build` копирует jar в образ (`Dockerfile:8`). 🟢
   🔴 Имена jar содержат `0.1.0-SNAPSHOT` (`:11,183,227,279`): при повышении версии проекта сборка образов сломается без правки compose.
2. **Entrypoint Java-образов** (`PD/entrypoint.sh:4-11`) 🟢: `set -eu`; каждый файл `/run/secrets/<ИМЯ>` экспортируется как переменная окружения `<ИМЯ>` (поэтому `target` секрета в compose = имя переменной Spring-конфигурации: `ARCHRAG_PG_PASSWORD`, `ARCHRAG_NEO4J_READER_PASSWORD`, …); затем `exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar "$@"` (аргументы `command` compose — `adapter EAM`, `stub SCM` и т. п. — уходят в `DemoLauncher`). Эффект: секреты не попадают в `Config.Env` (`docker inspect`), но видны процессу и `/proc/<pid>/environ` внутри контейнера. 🟡
3. **Порядок поднятия** (граф зависимостей — `flowcharts/docker.md`, схема 1) 🟢:
   `certs` → (`neo4j`, `reverse-proxy`); `neo4j` → `neo4j-init` (создаёт `archrag_reader`, `PD/neo4j/init-reader.sh:11-20`); `postgres` + `seaweedfs` + `neo4j` → `ingestion` (миграции Flyway и схема Neo4j накатываются при старте контекста; порт 8081 открывается позже — поэтому «любой HTTP-ответ» = готов, `:214-216`); `ingestion` → `console-pg-init` (роль `archrag_console_ro` создаёт миграция V6, `PD/postgres/init-console-ro.sh:9`) → `admin-console`; `ingestion` + `keycloak` + `neo4j-init` → `mcp-server`; `ingestion` + заглушки → адаптеры; `mcp-server` + `keycloak` + 4 адаптера + `certs` → `reverse-proxy`. Прокси стартует последним, поэтому `up --wait` возвращается только когда вся цепочка healthy. Keycloak ничего не ждёт и поднимается параллельно (около минуты, `start_period: 60s`, `PD/README.md:18`).
   🟡 `reverse-proxy` зависит от здоровья всех четырёх адаптеров (`:460-463`), хотя проксирует к ним только вебхуки: падение одного адаптера блокирует и вход MCP.
4. **Init-скрипты** 🟢:
   - `certs`: идемпотентен по маркеру `.ready` (`gen-certs.sh:7-10`); CA RSA 3072 на 825 дней (`:14-15`), серверные пары для `proxy` и `neo4j` с SAN `localhost, reverse-proxy, neo4j, 127.0.0.1` (`:12,17-26`); ключ CA — в отдельный том `ca-key`, доступный только `certs` (`:3-4` комментария, `compose:46`); владелец каталога Neo4j uid 7474, `chmod 600` ключей (`:33-37`).
   - `neo4j-init`: `CREATE USER archrag_reader IF NOT EXISTS` (`init-reader.sh:13-14`), `ALTER USER` только если вход reader'ом с текущим паролем не проходит (`:18-20`); отклоняет пароль с `"` и `\` (`:7-9`); выход ненулевой при любой ошибке (`set -euo pipefail`, `:4`).
   - `console-pg-init`: `ALTER ROLE archrag_console_ro PASSWORD :'p'`, пароль читается метакомандой psql из secret-файла, а не из argv/окружения (`init-console-ro.sh:2-3 комм., 8-9`).
5. **Образы одноразовых сервисов** `restart: "no"`; `docker compose up --wait` считает их завершившимися успешно через `service_completed_successfully` (`:78,243,296,297`).

### W1.4 Безопасность (ловушки CLAUDE.md 3, 12, 13)

**Секреты в Git (ловушка 12).**
- 🟢 В `docker-compose.yml` секретов нет: пароли и ключи — только `${VAR:?}` и блок `secrets: … environment:` (`:546-564`); `.env` исключён из Git (`.gitignore:31`). В Git лежит `.env.example` с демо-значениями (допускается CLAUDE.md репозитория/задачей; значения здесь не приводятся).
- 🟢 Realm-файл не содержит значений секретов: секрет клиента и пароль пользователя — подстановки `${KEYCLOAK_CLIENT_SECRET}` (`archrag-realm.json:56`) и `${KEYCLOAK_CONSOLE_PASSWORD}` (`:44`), подставляются Keycloak из `environment` (`compose:149,155`). Секреты в `.env.example` — демонстрационные, README требует сменить их перед внешней публикацией (`PD/README.md:216,228`).
- 🟢 Сделано правильно: секреты Java-сервисов, Postgres, Neo4j и Grafana — файлы `/run/secrets/*`, не `Config.Env` (`PD/README.md:106-108`). В скриптах: ключи S3 и секрет клиента для replay передаются через stdin (`lib.sh:53-60,101`), пароль консоли PG — метакомандой psql (`init-console-ro.sh:8`), токен — через временный файл заголовка (`lib.sh:110`).
- 🟡 Остаются в `environment` и видны в `docker inspect`: `AWS_*` SeaweedFS (`compose:124-125`), `KC_BOOTSTRAP_ADMIN_*` (`:141-142`) — README это признаёт (`PD/README.md:215`). 🔴 Но README не упоминает, что туда же попадают `KEYCLOAK_CLIENT_SECRET` и `KEYCLOAK_CONSOLE_PASSWORD` (`compose:149,155`, нужны только для подстановки в realm).
- 🔴 Секреты в argv (внутри контейнеров/на хосте), хотя заголовок `lib.sh:3` утверждает «Пароли и ключи в аргументы командной строки не попадают»: `cypher()` вызывает `cypher-shell -p "$(cat /run/secrets/neo4j_password)"` (`lib.sh:37-38`; то же в healthcheck Neo4j `compose:80`, в `platform/docker/neo4j/init-reader.sh:13,19` writer-пароль и `--param "p => …"` с reader-паролем); на хосте секрет клиента Keycloak идёт в `curl -d`/`--data-urlencode` (`smoke.sh:22`, `console-smoke.sh:68-69`, `scripts/deployment-commit-check.sh:26`), а пароль консоли — в `-d password=` (`console-smoke.sh:54-55`), хотя тот же скрипт для основного логина использует `password@файл` (`:33-35`). Видимость ограничена `ps` внутри контейнера/на хосте; в логи значения не выводятся. Комментарий «не попадает в argv» в `deployment-commit-check.sh:24` неточен.

**Neo4j: разделение writer/reader (ловушка 3).**
- 🟢 Раздельные креденшелы: writer (`neo4j`) выдан `ingestion` (`ARCHRAG_NEO4J_USER: neo4j`, `compose:192,204`) и `neo4j-init`; `mcp-server` и `admin-console` получают только `ARCHRAG_NEO4J_READER_USERNAME: archrag_reader` + `neo4j_reader_password` (`:233-234,239,285-289`). `smoke.sh:43-48` проверяет, что в окружении MCP нет `ARCHRAG_PG_*`, `NEO4J_PASSWORD`, `ARCHRAG_NEO4J_USER`, `ARCHRAG_S3_*`, а `/run/secrets` содержит ровно `ARCHRAG_NEO4J_READER_PASSWORD`; `console-smoke.sh:115-124` — то же для консоли (ровно два секрета).
- 🟡 Это **разделение креденшелов, а не прав**: в Community нет ролей (`init-reader.sh:3`, `PD/README.md:111-112`, ADR 0004/0005), `archrag_reader` технически может писать; read-only гарантирует только `graph-query-core` (`executeRead`, `AccessMode.READ`, см. X1). Стенд-уровневой защиты записи нет; `graph-fingerprint.sh` и `deployment-commit-check.sh` читают граф под **writer**-учёткой (`lib.sh:36-39`) — осознанно, как операторские скрипты.
- 🟢 Bolt только по TLS: `server.bolt.tls_level=REQUIRED` (`compose:62`), клиенты используют `bolt+ssc` (`:191,233,284`) — без проверки цепочки (демо-допущение, `PD/README.md:212`, gap 2.4); `smoke.sh:81-87` убеждается, что plain Bolt закрывается без ответа.

**TLS, прокси, порты наружу.**
- 🟢 Прокси слушает только `8443 ssl`, TLS 1.2/1.3 (`nginx.conf:7,10`); plain HTTP на порту не отвечает 200 (`smoke.sh:33-35`). Все 4 публикации — на `127.0.0.1`; 7474/7687/5432 с хоста закрыты (`smoke.sh:37-40`); консоль и Keycloak на не-loopback адресе хоста недоступны (`console-smoke.sh:98-112`). Админка Keycloak через прокси не проксируется — только `/realms/archrag/protocol/openid-connect/` (`nginx.conf:36-40`); `/control/snapshot` адаптеров наружу не публикуется (`nginx.conf:42`, UNLOCKER-169). Webhook идёт на фиксированный `/webhook` адаптера (`rewrite ^ /webhook break`, `:43-62`), подпись проверяет адаптер.
- 🟡 В `nginx.conf` нет заголовков безопасности (HSTS, CSP, X-Content-Type-Options), `limit_req`/`client_max_body_size`, `ssl_ciphers`: для `/mcp` лимит тела задаёт приложение (`max-request-bytes: 1MB`, `access/mcp-server/src/main/resources/application.yml`). **CSP есть только у admin-console** (`default-src 'self'; connect-src 'self' <origin issuer>; frame-ancestors 'none'; base-uri 'self'; object-src 'none'` + `nosniff`, `ConsoleSecurityConfiguration.java:34-37,61-66`); консоль отдаётся по обычному HTTP на loopback, не через прокси (ADR 0026), TLS для неё нет.
- 🟡 Через прокси публичен токен-endpoint Keycloak с демо-секретом `archrag-demo` (`PD/README.md:228`): допустимо только пока прокси на loopback. Прокси передаёт `Host $host` без порта и не ставит `X-Forwarded-Proto` (`nginx.conf:26,32,39`): Keycloak защищён от этого фиксированным `KC_HOSTNAME` (`compose:145`).
- 🟢 Сертификаты: самоподписанный CA без `keyUsage` (поэтому `measure.sh:41-42` отключает `VERIFY_X509_STRICT`), срок 825 дней без автоперевыпуска (`PD/README.md:213`); скрипты используют `--cacert` из тома, а не `-k` (`smoke.sh:12`; `-k` есть только в healthcheck прокси `compose:465`).
- 🟡 `edge` не internal: адаптеры, заглушки, MCP, Keycloak и прокси имеют исходящий доступ; в README это отмечено (`PD/README.md:218`).

**Ловушка 13 (scope на каждый tool).** Docker-часть вносит три обязательные переменные и сверяет их со Spring-конфигурацией (таблица в W1.5). Каждый tool `mcp-server` закрыт строкой в `archrag.mcp.security.tool-scopes` (все шесть — `architecture.read`, `application.yml:29-34`), отсутствие записи ⇒ отказ (fail-closed). Прокси сам не аутентифицирует, `/mcp` защищён JWT приложения; `smoke.sh:18-25` проверяет 401 без токена и 200 с токеном. Проверки неверного audience/scope на MCP покрыты тестами `McpSecurityTest`, не стендом. 🟢

### W1.5 Keycloak: клиенты, scopes, audience

Realm `archrag` (`archrag-realm.json:3-4`): `sslRequired: none` (HTTP, dev-режим `start-dev`, gap 2.1); импорт при первом старте контейнера (`--import-realm`, `compose:139`). Тома данных у Keycloak нет ⇒ при `--force-recreate` realm импортируется заново, а всё созданное вручную в админке теряется 🟡 (`PD/README.md:127` описывает обязанность пересоздавать контейнер).

**Client scopes** 🟢 (`:5-28`): `architecture.read` (без mapper'ов) и `architecture.admin` (mapper `admin-audience`, `oidc-audience-mapper`, `included.custom.audience = ${ARCHRAG_ADMIN_RESOURCE_URI}` = `urn:archrag:admin`, только access-token, `:17-22`).

**Клиенты** 🟢:

| Клиент | Тип / flow | Default scopes | Optional scopes | Audience-mapper (на клиенте) | Потребитель |
|---|---|---|---|---|---|
| `archrag-demo` (`:51-75`) | confidential, service account, `client_credentials`; standard/direct grants выключены (`:57-59`) | `architecture.read` (`:60`) | `architecture.admin` (`:61`) | `mcp-audience` → `${ARCHRAG_MCP_RESOURCE_URI}` = `https://localhost:${ARCHRAG_PUBLIC_PORT}/mcp` (`:64-69`) | MCP-клиенты/агенты, `smoke.sh`, `measure.sh`, `deployment-commit-check.sh`; с scope `architecture.admin` — `lib.sh:run_replay` (restore) |
| `archrag-admin-console` (`:77-109`) | public, Authorization Code + **PKCE S256** (`:86`), implicit/direct/service account выключены (`:82-85`) | — (пусто, `:89`) | `architecture.admin`, `architecture.read` (`:90`) | `console-audience` → `urn:archrag:console` (`:93-98`) + mapper `sub` (`:104`) | `admin-ui` (SPA `oidc-client-ts`), `console-smoke.sh` |

Redirect и web origin клиента консоли — только `http://127.0.0.1:${ARCHRAG_CONSOLE_PORT}` (`:87-88`), а не `localhost` (PKCE требует secure context, `PD/README.md:122`).

**Роль и пользователь.** Realm-роль `archrag-console-admin` (`:30-32`) и один пользователь (`:37-46`, имя и пароль из `KEYCLOAK_CONSOLE_USER/PASSWORD`, `emailVerified`, не временный пароль) с этой ролью. 🔴 **Роль нигде не проверяется**: код консоли требует только authority `SCOPE_architecture.admin` (`ConsoleSecurityConfiguration.java:25,44`), `scopeMappings` роль→scope в realm нет; любой пользователь realm получает токен консоли (признано владельцем: ADR 0026, `docs/05-production-gap-list.md:33`, gap 2.7).

**Сверка с кодом `access/*` и `ingestion`** 🟢:

| Контур | Ожидает приложение | Что даёт Keycloak/compose | Сверка |
|---|---|---|---|
| MCP (`mcp-server`) | issuer `ARCHRAG_OIDC_ISSUER_URI`, audience = `ARCHRAG_MCP_RESOURCE_URI`, scope `architecture.read` на каждый tool (`application.yml:17-34`) | issuer `http://keycloak:8080/realms/archrag` фиксирован `KC_HOSTNAME` (`compose:145,231`); `ARCHRAG_MCP_RESOURCE_URI` одинаков у Keycloak и mcp-server (`:150,232`); токен `archrag-demo` по умолчанию несёт aud MCP и scope read | совпадает |
| Консоль (`admin-console`) | audience `ARCHRAG_CONSOLE_RESOURCE_URI`, authority `SCOPE_architecture.admin` на `/api/**`; клиент `archrag-admin-console`, scope `openid architecture.admin` (`application.yml:15-22`, `ConsoleProperties.java:17-20`) | `urn:archrag:console` в compose (`:151,283`) и в mapper клиента; `client-id` совпадает с realm | совпадает |
| Admin REST ingestion | audience `ARCHRAG_ADMIN_RESOURCE_URI`, authority `SCOPE_architecture.admin` на `/admin/**`, дополнительно `azp ∈ ARCHRAG_ADMIN_ALLOWED_CLIENTS` (по умолч. `archrag-demo`; `application.yml:17-22`, `SecurityConfiguration.java:28-44`) | `urn:archrag:admin` (`compose:151,187`), mapper на scope `architecture.admin`; консольный клиент получает admin aud, но `azp=archrag-admin-console` ≠ `archrag-demo` ⇒ 401 | совпадает; проверяется `smoke.sh:59-79` (admin → 200, read → 401) и `console-smoke.sh:86-95` (токен консоли с admin aud → 401). `ARCHRAG_ADMIN_ALLOWED_CLIENTS` в compose не задан (берётся умолчание) 🟡 |

Токены: `iss` одинаков при получении напрямую или через прокси; для браузера консоли нужна запись `127.0.0.1 keycloak` в `/etc/hosts`, а `ARCHRAG_KEYCLOAK_PORT` должен быть 8080 (`PD/README.md:121`); `console-smoke.sh` обходит это через `curl --connect-to` (`:19`).

### W1.6 Наблюдаемость

🟢 Профиль `observability` (`compose:474,484,502`). Поток: `mcp-server` и `ingestion` (в сети `observability`, `compose:208,240`) шлют метрики OTLP/HTTP в `otel-collector:4318`, только если `ARCHRAG_OBSERVABILITY=true` (`MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`, `:200,235`; `otel-collector.yml:2-6`) → collector экспортирует для Prometheus на `:8889` с превращением resource-атрибутов в метки (`service.instance.id`, `:11-16`) → Prometheus скрейпит **только** collector, интервал 10 с (`prometheus.yml:2,6-8`), retention 2 дня (`compose:486`) → Grafana: datasource `Prometheus` (uid `prometheus`, по умолчанию, `provisioning/datasources/prometheus.yml:1-8`), provider папки «Arch RAG» (`provisioning/dashboards/archrag.yml:1-8`), дашборд `archrag-poc` (`archrag-poc.json:2-3`, обновление 10 с) из 4 панелей: p95 латентности tool (`archrag_mcp_tool_call_milliseconds_bucket`), sync lag по источнику, Error/DLQ rate (`archrag_ingestion_events_total{status=~"QUARANTINED|ERROR"}`, `archrag_ingestion_dlq_open`), throughput projection (`status="PROJECTED"`). Grafana — единственный порт профиля на хост, пароль из secret, анонимный доступ и регистрация выключены (`compose:505-508`).
🟡 В `otel-collector.yml:18-20` включено расширение `health_check` на `:13133`, но healthcheck compose его не использует (нет shell в образе, `:480-481`); готовность проверяется косвенно через цель Prometheus. Метрики имеют имена `archrag_*` — их определение в коде вне этого раздела (X2/Q5).

### W1.7 Скрипты (хостовые зависимости и коды выхода)

Все скрипты — bash с `set -euo pipefail`; ненулевой код — `die`/`fail`/необработанная ошибка (код 1). На хосте нужны Docker Compose v2 и `.env` рядом (`lib.sh:9`); `curl`, `python3` и `openssl` — только там, где указано. Из CI запускается только `smoke.sh` и `backup-restore-check.sh` (+ `shellcheck -S warning scripts/*.sh`, `.github/workflows/ci.yml:60-101`).

| Скрипт | Что проверяет / делает | Зависимости хоста | Выход |
|---|---|---|---|
| `smoke.sh` (89) | 401 на `/mcp` без токена; токен `client_credentials` через прокси → 200; вызов tool `ping`; plain HTTP на TLS-порту ≠ 200; 7474/7687/5432 закрыты; в `mcp-server` нет writer/PG/S3 окружения и секретов, кроме reader; положительные контроли (резолв `neo4j`, достижимость 7687) и отрицательные (нет `postgres`); прокси не резолвит `ingestion`; admin REST: scope admin → 200, read → 401; plain Bolt закрывается | docker, curl, python3 (`-c`) | 0 = «smoke ok…», 1 + `smoke FAILED: …` |
| `console-smoke.sh` (126) | вход Authorization Code + PKCE S256 на curl; direct grant у публичного клиента выключен (400/401); `/api/*` без токена → 401; токен с `architecture.read` → 403; агентский токен (aud MCP) → 401; admin-токен → 200 на `/api/graph/stats` и `/api/sync/sources` (ровно 4 источника); токен консоли на admin REST ingestion → 401 (с предпосылкой: aud admin и `azp` консоли); порты консоли/Keycloak на loopback и недоступны по внешнему IP (шаг пропускается без не-loopback адреса); прокси не резолвит `admin-console`; в контейнере ровно 2 секрета | docker, curl, openssl, python3 (`-I`), `hostname -I` | 0 / 1; шаг 6 может дать `skip`. 🔴 В CI не запускается |
| `scripts/lib.sh` (119) | общие функции: `dc`, `cypher` (cypher-shell в контейнере `neo4j` под writer), `vol_sh`, `neo4j_admin`, `aws_s3` (образ `amazon/aws-cli:2.27.50`, ключи через stdin), `psql_q`, `wait_queue_empty` (QUARANTINED ⇒ немедленная ошибка), `run_replay` (replay по 4 источникам `urn:corp:*` изнутри `ingestion`, токен scope `architecture.admin`) | docker | — |
| `scripts/backup.sh` (48) | метка (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, `lib.sh:31-33`) → остановка `neo4j`, `ingestion`, `mcp-server` → `neo4j-admin database dump neo4j` → подъём → `pg_dump -Fc` → `manifest.json` (метка, `backup_started_at`, версия Neo4j, sha256 двух дампов) → загрузка в `s3://$BACKUP_BUCKET/<метка>/` (бакет создаётся при отсутствии) | docker | 0, печатает метку; 1 при сбое |
| `scripts/restore.sh` (54) | скачивание из S3 → сверка sha256 и **точной версии** Neo4j с образом (до остановки сервисов) → остановка → `database load --overwrite-destination=true` → подъём → `POST /admin/replay` по 4 источникам за `[backup_started_at − 5 мин, now + 1 мин)` → ожидание пустой очереди (600 с). PostgreSQL **не** восстанавливается | docker | 0 / 1 |
| `scripts/graph-fingerprint.sh` (56) | отпечаток графа: число узлов по меткам и связей по типам + sha256 ключей узлов, связей и свойств (кроме `fetchedAt, lastSeenAt, firstSeenAt, observedAt, startedAt, finishedAt, _lock` и `SyncRun.status`) | docker | 0 + вывод |
| `scripts/backup-restore-check.sh` (53) | приёмка E5.4: F0 → backup → upsert двух объектов в `eam-api-stub` (новый `9001` и изменённый `5101`) → ожидание событий в `inbox_event` (до 300 с) → F1 (должен ≠ F0, иначе сценарий ничего не проверяет) → restore → F2 (должен = F1) | docker | 0 «OK…» / 1 с `diff` |
| `scripts/deployment-commit-check.sh` (133) | приёмка H.4 (UNLOCKER-248, ADR 0029): `find_runtime_footprint` по `Payments Core` → upsert развёртывания `dep-payments-api-prod` с другим commit в `stub-deploymap` → новый commit в MCP → в Neo4j ровно одно открытое `DEPLOYED_FROM` (новый commit) и одно закрытое (старый): `1,1,1`; идемпотентен (переключает commit туда-обратно) | docker, curl, python3 (`-I`) | 0 / 1. 🟡 Константы commit/URL/id зашиты и дублируют `StubSources`/`EamApiSeed` (`:13-17`) |
| `scripts/measure.sh` (381) | замеры критерия 9: p95 каждого tool (клиент и Prometheus; K=100, P=4), «источник → граф» по webhook (порог 30 с) и polling (интервал+5 с), burst M=200, sync lag, DLQ/QUARANTINED, `trace_dependencies` на глубинах 1…6, 7, 0 (7 и 0 обязаны быть отклонены, как в `CLAUDE.md`) | docker, bash, python3 (stdlib); стенд с профилем `observability` | 1 при: превышении порога p95, росте DLQ/QUARANTINED, ошибке tool, принятом `maxDepth` 0/7, отсутствии seed; иначе 0; таблица в stdout, сырьё в `measurements/<штамп>/` (в `.gitignore:32`) |

🟡 Особенность отказоустойчивости `backup.sh`/`restore.sh`: `trap … ERR` (`backup.sh:21`, `restore.sh:39`) не срабатывает на сбой **внутри функций** `lib.sh` (`stop_graph_services`, `neo4j_admin`, `start_graph_services`), потому что нет `set -E`. Локальный эксперимент с `set -euo pipefail`+`trap ERR`: при `false` внутри функции скрипт завершается с кодом 1 без запуска обработчика. Следствие: если `neo4j-admin dump/load` упадёт, `neo4j`, `ingestion`, `mcp-server` останутся остановленными, вопреки комментарию «граф должен подняться обратно» (`backup.sh:20`). Сбой на «верхнем» уровне (подстановки `$(…)`) обработчик запускает.
🟡 `backup.sh` останавливает/поднимает `neo4j`, `ingestion`, `mcp-server`, но не `admin-console` (читает Neo4j): во время dump консоль unhealthy, драйвер переподключается после старта. Пока Neo4j остановлен, MCP и ingestion недоступны (`PD/README.md:175-176`).

### W1.8 Версии образов и ограничения CLAUDE.md репозитория

| Требование | Факт | Статус |
|---|---|---|
| Neo4j 5.x Community, **точный тег** | compose: `neo4j:5-community` (`:52,90`) — плавающий; ITs: `neo4j:5.26.31-community` (`TestImages.java:9`) | 🔴 в compose тег не точный; последствие — `restore.sh` откажется читать копию после смены минорной версии (`PD/README.md:187-188`); известный техдолг, gap 1.7 |
| PostgreSQL | `postgres:16` (`:103,257`), ITs тоже `postgres:16` | 🟡 мажорная версия зафиксирована, минорная плавает |
| S3-совместимое хранилище (MinIO в тестах) | **SeaweedFS** `chrislusf/seaweedfs:4.48` (`:120`), в ITs тот же тег (`ContainersSmokeIT.java:35`) | 🟢 осознанное решение ADR 0007 (MinIO больше не публикует образы); формулировка про MinIO в общей инструкции агента устарела |
| Keycloak / nginx / otel / prometheus / grafana | `26.4` / `1.27-alpine` / `0.140.1` / `v3.7.3` / `12.2.1` / `alpine/openssl:3.5.4` / `amazon/aws-cli:2.27.50` | 🟢 тег указан, `latest` не используется (`lib.sh:15`) |
| Java 21 | `eclipse-temurin:21-jre` (`Dockerfile:3`) | 🟢; 🟡 тег плавающий в пределах 21 |
| Новые библиотеки без задачи | в Docker-слое Maven-зависимостей нет | 🟢 |

### W1.9 Сверка с `README.md` платформы, CLAUDE.md и docs/

🔴 Расхождения (документация ↔ конфигурация):
1. «Наружу публикуется только reverse-proxy» (`compose:2`; `PD/README.md:4,104`) — фактически публикуются 4 порта на loopback: прокси 8443, Keycloak 8080, консоль 8090, Grafana 3000 (`compose:161,293,451,516`); три из них — plain HTTP. README в других разделах это описывает (`:121,140`), но вводные утверждения устарели.
2. Таблица сетей README (`:96-101`) неполна: у `backend` нет `admin-console` и `console-pg-init` (`compose:267,291`); нет сетей `observability` и `obs-edge`; в списке «Что поднимается» (`:24-37`) нет `otel-collector`, `prometheus`, `grafana` (описаны ниже, `:129-152`).
3. `compose:214` («У ingestion-service нет actuator») и `PD/README.md:233` («actuator нужен только для метрик») — комментарий compose неточен: стартер actuator подключён (`ingestion-service/pom.xml:56-59`), эндпоинты закрыты `denyAll` (`application.yml:52` комментарий).
4. `PD/README.md:215` называет `environment`-секретами только `AWS_*` и bootstrap-админа Keycloak; `KEYCLOAK_CLIENT_SECRET`/`KEYCLOAK_CONSOLE_PASSWORD` тоже в `environment` Keycloak (`compose:149,155`).
5. `CLAUDE.md` (раздел «Демо-стенд»): «CI гоняет … `docker-stand` (весь список выше)» — в `ci.yml:60-101` нет `console-smoke.sh`, `scripts/deployment-commit-check.sh` и `scripts/measure.sh`; из `scripts/` запускается только `backup-restore-check.sh`. Консольная граница (ADR 0026) стендом в CI не проверяется.
6. `PD/README.md:125` описывает `console-smoke.sh` как проверку «консоль недоступна по внешнему IP»; скрипт проверяет и Keycloak (`console-smoke.sh:101-111`), параметры `E2E_JITTER_MAX` у `measure.sh` в README не описаны (`measure.sh:7`).
7. 🟢 Совпадают с кодом: сети, имена сервисов и секретов, раздельные reader/writer, audience-таблица, поведение backup/restore (версия Neo4j в manifest, replay-окно ±), поведение `measure.sh` (коды возврата, `N/M/K/P`), `E5` описание Keycloak-клиентов.

### W1.10 Сомнения и открытые вопросы (🔴/🟡)

| № | Вопрос | Ссылка | Кому |
|---|---|---|---|
| W1 | Плавающие теги `neo4j:5-community` (и `postgres:16`, `nginx`/`temurin` в пределах мажорной): нужен ли точный тег в compose (CLAUDE.md репозитория и ITs уже на `5.26.31`)? Иначе restore ломается при смене минорной версии | `docker-compose.yml:52,90`; `TestImages.java:9`; `docs/05-production-gap-list.md:21` | архитектор |
| W2 | `trap ERR` в `backup.sh`/`restore.sh` не поднимает граф обратно при сбое внутри функций `lib.sh` (проверено локальным экспериментом bash, не на стенде): нужен `set -E` или явный `trap … EXIT` | `scripts/backup.sh:21`, `restore.sh:39`, `lib.sh:5` | разработчик |
| W3 | Роль `archrag-console-admin` не принудительна, `architecture.admin` получает любой пользователь realm; Keycloak `start-dev` без тома и с master-админкой на loopback — принято владельцем для PoC | `archrag-realm.json:30-46`; ADR 0026; gap 2.7 | владелец (уже принято) |
| W4 | Секреты в argv: заголовок `lib.sh:3` и комментарий `deployment-commit-check.sh:24` расходятся с `lib.sh:38`, `smoke.sh:22`, `console-smoke.sh` (direct grant, agent token), `init-reader.sh:13,19` | перечислены в W1.4 | разработчик |
| W5 | Дрейф документации: «только reverse-proxy» в `compose:2` и `README:4,104`; неполная таблица сетей; CLAUDE.md обещает в CI весь список скриптов, а `console-smoke`/`deployment-commit-check`/`measure` в `ci.yml` отсутствуют | W1.9 | разработчик/архитектор |

Дополнительно (🟡, ниже по важности): jar-пути с `0.1.0-SNAPSHOT` в compose; `reverse-proxy` зависит от здоровья всех адаптеров; нет заголовков безопасности и лимитов на прокси; у `archrag_reader` права записи (ограничение Community); `backup.sh` не трогает `admin-console`; хардкод commit-констант в `deployment-commit-check.sh`.

### W1.11 Итоговая строка для таблицы итогов

| Модуль | Файлов / строк | Тестов | Ключевые алгоритмы | Основные риски (см. §) |
|---|---|---|---|---|
| `platform/docker` (не Maven-модуль) | 24 файла / ≈2 415 строк (compose 574, 8 bash-скриптов ≈1 000) | 0 unit; приёмка скриптами `smoke.sh`, `console-smoke.sh`, `backup-restore-check.sh`, `deployment-commit-check.sh`, `measure.sh` (в CI — первые и третий) | цепочка `depends_on` по healthcheck; Docker secrets → env через `entrypoint.sh`; три сети `internal` + loopback-публикация; холодный dump/load Neo4j + replay с проверкой sha256 и версии; отпечаток графа | W1.10: плавающий тег Neo4j, `trap ERR` вне функций, роль консоли не принудительна, секреты в argv, дрейф README/CLAUDE.md |
