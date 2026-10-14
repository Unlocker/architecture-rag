# contracts/event-schemas, Требования

> Reversa Writer, R.6a. Уровень модуля (granularity = hybrid). Сокращение: `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`, `EST/` = `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/`. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел. Use case: [`processing-status-lifecycle`](processing-status-lifecycle/requirements.md), [`journal-append-dedup-dlq`](journal-append-dedup-dlq/requirements.md), [`journal-keyset-read`](journal-keyset-read/requirements.md), [`source-version-compare`](source-version-compare/requirements.md).

## Зависимости модуля

Модуль: Java 21, Maven, артефакт `event-schemas`; единственная проектная зависимость `canonical-model` (`contracts/event-schemas/pom.xml:17-22`) 🟢. Фактически ни один класс модуля `canonical-model` не импортирует (grep по `ES/` не находит `canonicalmodel`) 🟢; зависимость объявлена, но не используется 🟡.

## Обзор

Контракт канонического события (CloudEvent 1.0 `architecture.asset.upserted.v1`) и четырёх портов: `EventJournal` (inbox, статусы, DLQ, checkpoint, snapshot), `JournalReader` (постраничное чтение), `RawPayloadStore` (S3-совместимый raw), `CanonicalEventPublisher` (граница варианта A/B). Модуль содержит только типы, интерфейсы и малую логику (`ProcessingStatus.canTransitionTo`, `SourceVersion`, конструкторные проверки); реализации лежат в `ingestion/event-journal` (`PostgresEventJournal`, `S3RawPayloadStore`) 🟢 (`ES/EventJournal.java:7-8`, `ES/RawPayloadStore.java:3`).

Источник правды о форме события и допустимых переходах статуса для адаптеров, normalizer, graph-projector, event-journal и ingestion-service.

## Ответственности
- Описать `CanonicalEvent` / `AssetEventData` / `SourceVersion` с инвариантами конструкторов 🟢 (`ES/CanonicalEvent.java:33-41`, `ES/AssetEventData.java:16-24`, `ES/SourceVersion.java:12-16`).
- Задать автомат `ProcessingStatus` (10 состояний) и функцию `canTransitionTo` 🟢 (`ES/ProcessingStatus.java:8-42`).
- Задать порты журнала, чтения, raw-хранилища и публикации с их гарантиями (Javadoc) 🟢.
- Задать DTO журнала: `JournalEntry`, `StoredEvent`, `Checkpoint`, `SnapshotContents`, `ObjectRef`, `RawPayloadRef`, `JournalKey`, `JournalQuery` 🟢.

## Правила (инварианты контракта)
- Ключ дедупликации события — пара `(source, id)`; повторный `append` ничего не меняет 🟢 (`ES/CanonicalEvent.java:8-9`, `ES/EventJournal.java:10-11,15-17`).
- `specversion` всегда `"1.0"` (метод, не компонент record) 🟢 (`ES/CanonicalEvent.java:28,44-46`).
- Версия источника хранится строкой, сравнивается как неотрицательное десятичное число до 40 цифр 🟢 (`ES/SourceVersion.java:6-8,13,19-21`).
- `null` в `payload` означает «поле не передано» и сохраняется; он не должен превращаться в удаление 🟢 (`ES/AssetEventData.java:10-12,22-23`).
- Переходы статуса проверяет `canTransitionTo`; переход в себя разрешён всегда 🟢 (`ES/ProcessingStatus.java:26-29`).
- Checkpoint сдвигается только после коммита проекции; монотонность курсора журнал не проверяет 🟢 (`ES/Checkpoint.java:6`, `ES/EventJournal.java:46-49`).
- Объекты snapshot берутся из журнала (`snap:<syncRunId>:...`), а не из графа, и включают события в `DUPLICATE`/`QUARANTINED`/`RETRYING` 🟢 (`ES/EventJournal.java:52-56`, `ES/SnapshotContents.java:9-11`).
- `errorReason` в `JournalEntry` — краткая причина без содержимого источника 🟢 (`ES/JournalEntry.java:11`).
- Ошибки хранилища не глушатся (инвариант Javadoc `EventJournal` и `JournalReader`) 🟢 (`ES/EventJournal.java:10-11`, `ES/JournalReader.java:9-10`).
- Типы событий `architecture.asset.deleted.v1` и `architecture.sync.snapshot-complete.v1` в контракте не объявлены; тип события — любая непустая строка 🟢 (`ES/CanonicalEvent.java:31,36`) 🔴 как пробел контракта, см. R-01.

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `CanonicalEvent` отвергает (`IllegalArgumentException`) пустые/`null` `id`, `source`, `type`, `dataschema` и `null` `time`/`data`; `subject`, `correlationid` не проверяются 🟢 | Must | AC-01, AC-02 |
| RF-02 | `AssetEventData` требует непустые `sourceType`, `sourceId` и не-`null` `sourceVersion` (IAE); `payload` `null` → пустая неизменяемая карта; копия, сохраняющая `null`-значения и порядок вставки 🟢 | Must | AC-03, AC-04 |
| RF-03 | `SourceVersion` принимает только `[0-9]{1,40}`; сравнение числовое (`BigInteger`); `isOlderThan` — строго меньше 🟢 | Must | [`source-version-compare`](source-version-compare/requirements.md) |
| RF-04 | `ProcessingStatus.canTransitionTo` реализует таблицу переходов из [`design.md`](design.md) §«Автомат» 🟢 | Must | [`processing-status-lifecycle`](processing-status-lifecycle/requirements.md) |
| RF-05 | `EventJournal` описывает атомарный идемпотентный `append`, `find`, `transition`, `toDlq`, `loadCheckpoint`/`saveCheckpoint`, `snapshotContents`, `objectsReceivedSince` 🟢 | Must | [`journal-append-dedup-dlq`](journal-append-dedup-dlq/requirements.md) |
| RF-06 | `JournalReader` отдаёт страницы в порядке `(receivedAt, source, eventId)` keyset-ом, `pending` — необработанные строки 🟢 | Must | [`journal-keyset-read`](journal-keyset-read/requirements.md) |
| RF-07 | `RawPayloadRef` требует непустые `key` и `contentHash` (IAE) 🟢 | Must | AC-05 |
| RF-08 | `RawPayloadStore.put(source, bytes)` кладёт под ключом `raw/<source>/<sha256>` идемпотентно; `get` проверяет hash и при расхождении бросает исключение 🟢 (тип исключения не указан 🟡) | Must | контракт порта; тестов в модуле нет |
| RF-09 | `CanonicalEventPublisher.publish` — at-least-once, `true` при первом приёме, `false` для дубликата 🟢 | Should | контракт порта; реализация есть — `PostgresEventJournal.java:38,100` (`implements … CanonicalEventPublisher`), но `.publish(` нигде не вызывается 🟢 `[Ревью R.7]` |
| RF-10 | `SnapshotContents` копирует `objects` через `Set.copyOf` 🟢 (`ES/SnapshotContents.java:16-18`) | Should | DTO-инвариант; теста нет |
| RF-11 | `JournalKey` и `StoredEvent` отвергают `null` обязательных полей (`NullPointerException`) 🟢 | Should | теста нет |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Совместимость | Неизменяемые `record`-типы; `payload` — неизменяемая обёртка над копией | `ES/AssetEventData.java:14,23` | 🟢 |
| Корректность времени | Все моменты — `java.time.Instant` (UTC) | `ES/CanonicalEvent.java:22`, `ES/Checkpoint.java:13`, `ES/JournalEntry.java:28-29` | 🟢 |
| Масштабируемость обхода | Размер страницы журнала ограничен `MAX_LIMIT = 1000` | `ES/JournalQuery.java:25,28-30` | 🟢 |
| Расширяемость | Граница варианта A/B: смена реализации (PostgreSQL ↔ Kafka) не меняет адаптеры | `ES/CanonicalEventPublisher.java:3-6`, `ES/EventJournal.java:8` | 🟡 (в коде только заявление Javadoc) |
| Безопасность | Тексты источников недоверенные: `payload` не доверяется, `errorReason` без содержимого источника | `ES/AssetEventData.java:10-11`, `ES/JournalEntry.java:11` | 🟢 |

> Нефункциональные требования без evidence (производительность, SLA) не включены.

## Критерии приёмки

```gherkin
# AC-01
Дано id = " " (пробелы), остальные поля CanonicalEvent валидны
Когда создаётся CanonicalEvent
Тогда выбрасывается IllegalArgumentException("id is required")           # CanonicalEventTest.rejectsMissingId

# AC-02
Дано CanonicalEvent с time = null (или data = null)
Когда вызывается конструктор
Тогда IllegalArgumentException("time and data are required")             # теста нет 🟡

# AC-03
Дано AssetEventData с sourceId = ""
Когда создаётся запись
Тогда IllegalArgumentException("sourceId is required")                   # CanonicalEventTest.dataRequiresSourceId

# AC-04
Дано payload = {name:"x", language:null}, затем в исходную карту добавлен ключ "late"
Когда создан AssetEventData
Тогда payload() содержит language -> null и не содержит "late"           # AssetEventDataTest.payloadKeepsNullValuesAsNotProvided

# AC-05
Дано RawPayloadRef(key="k", contentHash=" ")
Когда создаётся ссылка
Тогда IllegalArgumentException("key and contentHash are required")      # теста нет 🟡

# AC-06
Дано валидный CanonicalEvent без correlationid
Когда вызывается specversion()
Тогда возвращается "1.0", а data().sourceVersion().value() == "184"      # CanonicalEventTest.buildsEnvelope
```

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| RF-01..RF-06 | Must | используются всеми контурами `adapters`, `ingestion` (spec-impact-matrix §1) |
| RF-07, RF-08 | Must | raw payload — основа replay и reconcile |
| RF-09 `CanonicalEventPublisher` | Should | в рантайме нет реализаций; задел под Kafka (ADR 0006) |
| RF-10, RF-11 | Should | малые DTO, нет тестов |
| Объявление констант `deleted` / `snapshot-complete` в контракте | Could | предложение R.2a, в коде не реализовано |
| Статус `SUPERSEDED` | Удалён (UNLOCKER-281) | ни один код не переводил в него (Р-1) |

## Открытые вопросы и расхождения (🔴)

| ID | Что | Evidence |
|----|-----|----------|
| R-01 | Типы `architecture.asset.deleted.v1`, `architecture.sync.snapshot-complete.v1` и их `dataschema` не вынесены в контракт; литералы продублированы в `EventMapper.java:29-32`, `EventProcessor.java:92-94`, `StoredEventReader.java:34`, `ReplayService.java:37` | `ES/CanonicalEvent.java:31`; `tech-debt.md` Н-5; `traceability/spec-impact-matrix.md` §2 № 1 |
| R-02 | 🟢 `SUPERSEDED` удалён (UNLOCKER-281); раньше был определён и перечислен в `FINISHED`, но никто не переводил в него | `ES/ProcessingStatus.java:16,39`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:99-105`; `detective-gaps.md` Р-1 |
| R-03 | Выхода из `QUARANTINED` через оператора нет: переход `QUARANTINED → RETRYING` разрешён контрактом, но админский REST его не реализует, `EventProcessor` пропускает `QUARANTINED` как конечный (`FINISHED`) | `ES/ProcessingStatus.java:38`; `EventProcessor.java:99-105,190-192`; `detective-gaps.md` Р-2 |
| R-04 | Структура id `snap:<syncRunId>:<type>/<id>/v<version>` и префикс `reconcile:` не зафиксированы константами контракта; в контракте только `REPLAY_PREFIX`; литерал `"snap:"` дублируется в `PostgresEventJournal.java:284` и `EventMapper.java:104` | `ES/JournalQuery.java:22`, `ES/EventJournal.java:53` |
| R-05 | Расхождение `sourceVersion`: `SourceChange.sourceVersion` — `long`, в контракте журнала — до 40 цифр; значения выше `Long.MAX_VALUE` в `source-spi` невыразимы | `.reversa/context/modules.json` (запись `source-spi`, `SourceChange.java:27`) 🟡 |
