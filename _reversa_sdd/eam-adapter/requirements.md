# adapters/eam-adapter — требования

> Reversa Writer, R.6a. Пути — от корня репозитория. `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел. Сценарии: [`fetch-changes/`](fetch-changes/requirements.md), [`composite-sync/`](composite-sync/requirements.md), [`eam-api-request/`](eam-api-request/requirements.md). Внешние форматы — [`contracts.md`](contracts.md), решения — [`decisions.md`](decisions.md), вопросы — [`questions.md`](questions.md).

## Визуальный обзор

Модуль привязывает общий каркас адаптеров (`adapter-core`: webhook, polling, snapshot, запись в inbox) к источнику `SourceSystem.EAM` (`EA/EamAdapter.java:19`). Своей логики приёма событий у модуля нет: он поставляет **коннектор** — читателя `eam-api` («EAM Tool API», `docs/eam-api.yaml`, формат `ArchObject`) и склейку с «старой заглушкой» (`TEAM`, `SERVICE_DEPENDENCY`). Доступа к Neo4j, PostgreSQL и S3 у модуля нет: журнал и raw-хранилище приходят интерфейсами `event-schemas` (`EA/EamAdapter.java:33-39, 62-71`) 🟢.

## Responsibilities

- Читать списки `platforms`, `itproducts`, `itsystems` реального формата EAM постранично и отдавать `SourceChange` с `sourceType ∈ {PLATFORM, SOLUTION, IT_SYSTEM}` и `sourceId = id` объекта 🟢 (`EA/EamApiConnector.java:51-54, 175-183`).
- Вести непрозрачный курсор: полный snapshot, инкрементальный проход по `sort=-mtime` до водяного знака и состояние «между проходами» 🟢 (`EA/EamApiCursor.java`).
- Соблюдать лимиты источника: `page_size ≤ 500`, ≤ 10 запросов/с, одно ожидание по `Retry-After` при 429 🟢 (`EA/EamApiConnector.java:45, 56-57, 205-219, 249-260`).
- Склеить `eam-api` и legacy-заглушку в один коннектор одного источника `EAM` с одним snapshot и одним маркером `snapshot-complete` 🟢 (`EA/CompositeEamConnector.java:50-70`).
- Собрать `SourceAdapter` для `EAM` в двух режимах: «только legacy» и «`eam-api` + legacy» 🟢 (`EA/EamAdapter.java:33-39, 62-71`).
- Не светить токен: `EamApi.toString`, исключения и логи без токена и тел ответов 🟢 (`EA/EamAdapter.java:49-52`, `EA/EamApiConnector.java:186, 244`).

Не входит в модуль: разбор `attrs` и построение `GraphCommand` (это `ingestion/normalizer`, `EamMapper`), запись в inbox/S3 (`adapter-core`), reconcile и tombstone (`graph-projector`, `Reconciler`), сами заглушки (`platform/source-stubs`).

## Бизнес-правила

- BR-1. `sourceId` — целочисленный `id` объекта EAM без префикса: `Long.toString(id)`; строковый или нецелый `id` — ошибка 🟢 (`EA/EamApiConnector.java:175, 182, 190-195`; тест `EamApiConnectorTest.malformedIntegerIdIsRejected`, `ET/EamApiConnectorTest.java:212`; решение владельца 2026-10-04, `docs/06-eam-integration.md` §«Схема синхронизации», п. 3).
- BR-2. Порядок чтения типов фиксирован: `PLATFORM → SOLUTION → IT_SYSTEM` 🟢 (`EA/EamApiConnector.java:51-54`).
- BR-3. Тип EAM для «ИТ-решения» — `itproducts`, единственное место выбора — константа `SOLUTION_TYPE` 🟢 (`EA/EamApiConnector.java:48`); подтверждено владельцем 2026-10-10 (`docs/06-eam-integration.md` §«Решения», п. 1).
- BR-4. Коннектор отдаёт только `UPSERT` с `Completeness.COMPLETE`; `DELETE` не порождается. Исчезновение объекта (архивация) обнаруживает snapshot + reconcile (ADR 0020) 🟢 (`EA/EamApiConnector.java:182-183`).
- BR-5. `snapshotComplete=true` — только на последней странице последнего типа полного прохода (`cursor == null`) 🟢 (`EA/EamApiConnector.java:131`); в составном коннекторе — только после последней страницы legacy-половины 🟢 (`EA/CompositeEamConnector.java:65-69`).
- BR-6. Курсор нового формата: `W,<watermark>` между проходами. Водяной знак — максимальный `datetime`, увиденный коннектором; он не убывает внутри прохода 🟢 (`EA/EamApiConnector.java:111, 120-122`, `EA/EamApiCursor.java:39-45`).
- BR-7. Чужой (не составной) курсор в `CompositeEamConnector` не падает навсегда: чтение идёт заново **как инкремент** (без `snapshot-complete` и без удалений) 🟢 (`EA/CompositeEamConnector.java:72-85`).
- BR-8. Типы legacy-половины ограничены `TEAM`, `SERVICE_DEPENDENCY`; `IT_SYSTEM` (и любой иной тип) из legacy отбрасывается молча 🟢 (`EA/CompositeEamConnector.java:30, 61`).
- BR-9. `datetime == null` превращается в `Instant.EPOCH` 🟢 (`EA/EamApiConnector.java:180-181`); последствия — [`questions.md`](questions.md) Q-3.
- BR-10. Для маршрутизации `fetchById` важен только `sourceType`: `PLATFORM`/`SOLUTION`/`IT_SYSTEM` → `eam-api`, `TEAM`/`SERVICE_DEPENDENCY` → legacy, прочее — пусто 🟢 (`EA/CompositeEamConnector.java:88-96`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|---|---|---|---|
| RF-01 | `EamApiConnector.fetchChanges(null, limit)` читает полный snapshot трёх типов в порядке BR-2 с `sort=id` и ставит `snapshotComplete` на последней странице 🟢 | Must | `EamApiConnectorTest.snapshotReadsThreeTypesWithIntegerIdAsSourceId` (`ET/EamApiConnectorTest.java:84`), `snapshotCompleteOnlyOnLastPageOfLastType` (`:115`) |
| RF-02 | Конец типа — страница короче `size` (в том числе пустая); страница ровно `size` ведёт к запросу следующей 🟢 | Must | `paginationWithFullLastPageContinuesToEmptyPage` (`:98`), `paginationWithPartialLastPageStopsImmediately` (`:107`) |
| RF-03 | Инкремент читает тип по `sort=-mtime` и останавливается на первом объекте с `datetime ≤ from`; новый курсор `W,<max datetime>` 🟢 | Must | `incrementalReturnsOnlyObjectsChangedSinceWatermark` (`:133`), `incrementalNextCursorMovesWatermarkForward` (`:146`) |
| RF-04 | `fetchById(type, id)` читает `GET /api/<type>/<id>/`; 404 → пусто; нечисловой `id` или неизвестный тип → пусто без запроса 🟢 | Should | `fetchByIdReadsObjectAndReturnsEmptyFor404` (`:190`) |
| RF-05 | Запросы не чаще 10 в секунду (интервал 100 мс) 🟢 | Must | `requestsAreSpacedToTenPerSecond` (`:275`) |
| RF-06 | 429 с `Retry-After` — одно ожидание (≤ 30 с) и повтор; затем `SourceUnavailableException`; 429 без заголовка и `5xx` — сразу `SourceUnavailableException` 🟢 | Must | `rateLimitWithRetryAfterWaitsOnceAndRetries` (`:222`), `repeatedRateLimitSurfacesAsSourceUnavailable` (`:241`), `rateLimitWithoutRetryAfterAndServerErrorAreNotRetriedInside` (`:259`) |
| RF-07 | `page_size` зажимается до 500 🟢 | Must | `pageSizeIsClampedTo500` (`:282`) |
| RF-08 | Пустой/пробельный токен и `pageSize < 1` отвергаются в конструкторе 🟢 | Must | `blankTokenIsRefused` (`:185`); для `pageSize < 1` теста нет 🟡 |
| RF-09 | Неверный токен (401) — `IllegalStateException` без токена и тела в сообщении 🟢 | Must | `wrongTokenIsRejectedWithoutLeakingIt` (`:177`) |
| RF-10 | Составной snapshot: сначала `eam-api`, потом legacy; маркер только после legacy; `IT_SYSTEM` legacy отброшен 🟢 | Must | `CompositeEamConnectorTest.snapshotCoversBothHalvesAndDropsLegacyItSystem` (`ET/CompositeEamConnectorTest.java:73`), `snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf` (`:82`) |
| RF-11 | Составной инкремент читает обе половины по очереди и не ставит маркер 🟢 | Must | `incrementalFromSnapshotCursorSeesChangesOfBothHalves` (`:119`), `incrementalWithoutChangesFinishesWithStableCursor` (`:134`) |
| RF-12 | Legacy без `snapshot-complete` в snapshot — `IllegalStateException`, маркер не ставится 🟢 | Must | `legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker` (`:161`) |
| RF-13 | Чужой курсор — инкрементальное перечитывание без маркера 🟢 | Must | `foreignCursorOfTheOldAdapterRestartsIncrementalReadWithoutSnapshotMarker` (`:186`) |
| RF-14 | Страница `eam-api` без `nextCursor` — ошибка 🟢 | Should | `upstreamPageWithoutCursorIsAnError` (`:197`) |
| RF-15 | Фабрики `EamAdapter.create` отвергают конфигурацию чужого источника `IllegalArgumentException` 🟢 | Must | `EamAdapterContractTest.adapterIsBoundToItsSource` (`ET/EamAdapterContractTest.java:40`) — только перегрузка без `EamApi` 🟡 |
| RF-16 | `EamApi.toString` маскирует токен 🟢 | Must | прямого теста нет 🟡 → [`tasks.md`](tasks.md) TT-14 |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Производительность / лимиты источника | ≤ 10 запросов/с, `page_size ≤ 500` | `EA/EamApiConnector.java:45, 56, 249-260` | 🟢 |
| Производительность | таймаут соединения 5 с, запроса 10 с | `EA/EamApiConnector.java:64, 225` | 🟢 |
| Доступность | один повтор после 429 с `Retry-After`, ожидание ≤ 30 с; остальные повторы — на `RetryPolicy` poller'а через `SourceUnavailableException` | `EA/EamApiConnector.java:57, 205-219`; `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java:190-205` | 🟢 |
| Безопасность | токен только в заголовке `Authorization: Token`; не в `toString`, исключениях, логах | `EA/EamApiConnector.java:81, 227`; `EA/EamAdapter.java:49-52`; `EA/EamApiConnector.java:243-245` | 🟢 |
| Безопасность | тела ответов источника (недоверенные) не копируются в исключения | `EA/EamApiConnector.java:185-186, 243-245` | 🟢 (оговорка: `DateTimeParseException` в `cause`, см. [`questions.md`](questions.md) Q-5) |
| Безопасность | адаптер только читает: используется лишь `GET` | `EA/EamApiConnector.java:228` | 🟢 |
| Безопасность | защита от подстановки в путь: `sourceId` разбирается `Long.parseLong` и подставляется как число | `EA/EamApiConnector.java:138-147`; `ET/EamApiConnectorTest.java:195` | 🟢 |
| Идемпотентность | перекрытие страниц инкремента гасит событие `poll:<type>/<id>/v<ver>` (на стороне `adapter-core`) | `EA/EamApiConnector.java:34-35`; `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java:98-100` | 🟢 |
| Потокобезопасность | `throttle` синхронизирован; `HttpClient` один на коннектор | `EA/EamApiConnector.java:64, 249` | 🟢 |
| Время | UTC, `Instant`; в курсоре — ISO-8601 без запятых | `EA/EamApiCursor.java:7-14`, `EA/EamApiConnector.java:180-181` | 🟢 |

> Инфраструктурных NFR (память, масштабирование) в коде модуля нет — строки не добавлены.

## Критерии приёмки (модульные)

```gherkin
Сценарий: полный snapshot составного источника
  Дано источник eam-api с 2 платформами, 2 решениями, 2 системами и legacy-заглушка с TEAM и SERVICE_DEPENDENCY
  Когда poller читает cursor == null постранично с limit 2 до hasMore == false
  Тогда страницы содержат PLATFORM, SOLUTION, IT_SYSTEM (sourceId = id), затем TEAM и SERVICE_DEPENDENCY
  И snapshotComplete == true только у последней страницы
  # CompositeEamConnectorTest.snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf

Сценарий: legacy не подтвердил полноту
  Дано legacy-половина, которая заканчивает проход без snapshotComplete
  Когда составной коннектор читает snapshot до конца
  Тогда выбрасывается IllegalStateException "legacy eam ended snapshot without snapshot-complete"
  И маркер snapshot-complete не отдаётся (reconcile не удалит живые записи)
  # CompositeEamConnectorTest.legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker

Сценарий: неверный токен
  Дано eam-api, отвечающий 401
  Когда коннектор читает список
  Тогда IllegalStateException со статусом 401 и без значения токена в сообщении
  # EamApiConnectorTest.wrongTokenIsRejectedWithoutLeakingIt

Сценарий: пустой токен
  Когда создаётся EamApiConnector с токеном из пробелов
  Тогда IllegalArgumentException
  # EamApiConnectorTest.blankTokenIsRefused

Сценарий: фабрика с конфигурацией чужого источника
  Дано AdapterConfig для SCM
  Когда вызывается EamAdapter.create(config, journal, rawStore)
  Тогда IllegalArgumentException
  # EamAdapterContractTest.adapterIsBoundToItsSource
```

Остальные Дано/Когда/Тогда — в use case папках.

## Приоритет (MoSCoW)

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-03 (snapshot, пагинация, инкремент) | Must | единственный путь EAM-данных в граф |
| RF-05…RF-07 (лимиты) | Must | допущение 6 `docs/06-eam-integration.md`; обязательство перед владельцем EAM |
| RF-10…RF-13 (составной коннектор) | Must | иначе `Reconciler` снесёт записи другой половины |
| RF-09, RF-16 (секреты) | Must | правило безопасности проекта |
| RF-04 (`fetchById`) | Should | нужен webhook-пути, которого в реальном EAM нет (`docs/06-eam-integration.md` §«Схема синхронизации») |
| RF-14 (страница без курсора) | Should | защита от некорректной половины |
| Legacy-половина целиком | Could → удалить | «долг PoC», убирается задачей про реальный EAM (`EA/CompositeEamConnector.java:15-17`) |
| Генерация `DELETE` из коннектора | Won't | намеренно не реализовано (BR-4) |

## Rastreabilidade de código

| Файл | Класс | Покрытие |
|---|---|---|
| `EA/EamApiConnector.java` | `EamApiConnector` | 🟢 |
| `EA/EamApiCursor.java` | `EamApiCursor` | 🟢 |
| `EA/CompositeEamConnector.java` | `CompositeEamConnector`, `State` | 🟢 |
| `EA/EamAdapter.java` | `EamAdapter`, `EamAdapter.EamApi` | 🟢 |
| `EA/EamAdapterModule.java`, `EA/package-info.java` | маркер модуля | 🟢 (`EamAdapterSmokeTest`, `ET/EamAdapterSmokeTest.java:10`) |
| `adapters/eam-adapter/src/test/resources/eam-api/**` | fixtures формата EAM | 🟢 (`EamApiFixturesContractTest`, `ET/EamApiFixturesContractTest.java:75-215`) |
