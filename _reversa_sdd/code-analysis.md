# Code Analysis → Технический анализ кода

> Archaeologist, этап R.2a (UNLOCKER-263). Охвачены модули `contracts/canonical-model`, `contracts/event-schemas`, `contracts/source-spi`.
> Остальные модули (R.2b–R.2e) добавят свои разделы позже.
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
