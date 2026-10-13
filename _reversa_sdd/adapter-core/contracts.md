# adapters/adapter-core, Контракты

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Внешние интерфейсы модуля: HTTP-вход (webhook, control), HTTP-выход (источник), события в журнал, raw в S3, checkpoint-ы. Метки: 🟢/🟡/🔴.

## 1. HTTP-вход: `WebhookServer`

JDK `HttpServer`, виртуальные потоки (`AC/WebhookServer.java:52-53`). Адрес задаёт вызывающий код (`SourceAdapter.startWebhook(InetSocketAddress)`); порт 0 допустим (тесты). `endpoint()` возвращает `http://<host>:<port>/webhook` (`:62-65`).

### 1.1 `POST /webhook` (`AC/WebhookServer.java:54, 96-123`)

Заголовки (`contracts/source-spi/.../WebhookSignature.java`): `X-Archrag-Signature: sha256=<hex>`, `X-Archrag-Timestamp` (unix-секунды), `X-Archrag-Event-Id`. Подпись: `HMAC-SHA256(secret, timestamp + "." + body)`, тело берётся как строка UTF-8 «как есть» 🟢.

Тело (JSON-объект), используемые ключи 🟢 (`AC/WebhookHandler.java:74-83, 93-100`):

| Ключ | Тип | Правило |
|---|---|---|
| `eventId` | string, не blank | обязателен; должен совпасть с `X-Archrag-Event-Id` |
| `source` | string | обязателен; равен `system.code()` адаптера (**код**, не URN), иначе `400` |
| `sourceType`, `sourceId` | string, не blank | обязательны |
| `operation` | string | читается только как `"DELETE"` (`:93`) |
| `sourceVersion` | число | читается только для `DELETE`, если источник объект не знает; `null`/не число/`< 0` → `400` |
| остальное (`occurredAt` и др.) | — | игнорируется |

Ответы (пустое тело; `sendResponseHeaders(status, -1)`, `:104`) 🟢:

| Код | Условие | Место |
|---|---|---|
| 202 | событие записано в inbox (в т. ч. `DUPLICATE`) | `AC/WebhookHandler.java:105-106` |
| 400 | тело не JSON-объект; нет/blank `eventId`/`sourceType`/`sourceId`; `source` ≠ код адаптера; `eventId` ≠ заголовок; `DELETE` без валидной `sourceVersion` | `:71-73, 77-83, 95-98` |
| 401 | подпись/timestamp отсутствуют, неверны, вне окна (`MALFORMED`, `BAD_SIGNATURE`, `OUT_OF_WINDOW` неразличимы) | `:65-67` |
| 404 | `fetchById` пуст и `operation` ≠ `DELETE`; ничего не пишется | `:101-103` |
| 405 | метод не `POST` (без заголовка `Allow`) | `AC/WebhookServer.java:99-100` |
| 413 | тело > 65 536 байт | `:113-115` |
| 500 | необработанное `RuntimeException` (сбой журнала/S3, `IllegalStateException` коннектора, `IllegalArgumentException` маппера); логируется только класс | `:119-122` |
| 503 | `SourceUnavailableException` при `fetchById` | `AC/WebhookHandler.java:87-89` |

Порядок проверок: 413 (до обработчика) → 401 → 400 (JSON) → 400 (поля) → fetchById (503) → 404/400 (DELETE) → запись → 202 🟢.

### 1.2 `POST /control/snapshot` (`AC/WebhookServer.java:55-57, 67-94`)

Создаётся только если передан `Supplier<PollResult>` (`SourceAdapter` всегда передаёт `poller::snapshotOnce`, `AC/SourceAdapter.java:63`). **Аутентификация (UNLOCKER-290):** `Authorization: Bearer <JWT>`, проверка `ControlTokenValidator` (issuer, подпись по JWKS RS256, срок, audience, realm-роль `archrag-console-admin`): нет/недействителен — `401`, нет роли — `403`; без валидатора (не заданы `ARCHRAG_CONTROL_*`) контекст не создаётся 🟢. Тело запроса не читается. Синхронный вызов `Poller.snapshotOnce()`.

| Код | Условие |
|---|---|
| 202 | `SNAPSHOT_COMPLETED`, а также любой иной исход, кроме двух ниже (`default`, в т. ч. `UP_TO_DATE`, `ADVANCED`) |
| 409 | `ALREADY_RUNNING` |
| 503 | `GAVE_UP` |
| 405 | метод не `POST` (тело пустое) |
| 500 | `RuntimeException` (тело `{}`, лог `snapshot failed: <класс>`) |

Тело при 202/409/503: `{"outcome":"<ИМЯ>","appended":<long>,"syncRunId":"<id или пустая строка>"}`, `Content-Type: application/json` (`:77-93`) 🟢. Порядок ключей не гарантирован (`Map.of`) 🟡.

Потребитель: `ingestion` `POST /admin/reconcile/{source}` (таймаут 10 мин по `_reversa_sdd/integrations.md` I-4; `docs/03-interfaces.md:368-378, 400`) 🟡.

## 2. События в журнал (`EventJournal.append`)

Все — `CanonicalEvent` (CloudEvents 1.0: `specversion` всегда `1.0`). Поля, которые заполняет адаптер 🟢:

| Поле | `upserted` | `deleted` | `snapshot-complete` |
|---|---|---|---|
| `id` | `poll:<type>/<id>/v<ver>` · `snap:<runId>:<type>/<id>/v<ver>` · `eventId` webhook | то же | `snapshot-complete:<syncRunId>` |
| `source` | `urn:corp:<code>` | то же | то же |
| `type` | `architecture.asset.upserted.v1` | `architecture.asset.deleted.v1` | `architecture.sync.snapshot-complete.v1` |
| `subject` | `<sourceType lower, '_'→'-'>/<sourceId>` | то же | `sync-run/<syncRunId>` |
| `time` | `change.updatedAt()` | то же (webhook-tombstone: `clock.instant()`) | `clock.instant()` на момент записи маркера |
| `dataschema` | `urn:corp:schema:asset-upserted:1` | `urn:corp:schema:asset-deleted:1` | `urn:corp:schema:snapshot-complete:1` |
| `correlationid` | polling: `null`; snapshot: `syncRunId`; webhook: `eventId` | то же | `syncRunId` |
| `data.sourceType` / `sourceId` | как у изменения | то же | `SYNC_RUN` / `<syncRunId>` |
| `data.sourceVersion` | `Long.toString(version)`, `[0-9]{1,40}` | то же | `"1"` |
| `data.payload` | поля источника без `null` верхнего уровня + `_completeness` | пусто | `{syncRunId: string, objectCount: long}` |
| столбец `sync_run_id` журнала | polling/webhook: `null`; snapshot: `syncRunId` | — | `syncRunId` |

Места: `AC/EventMapper.java:29-42, 52-109`; передача `correlationId`/`syncRunId`: `AC/Poller.java:127, 154, 169`, `AC/WebhookHandler.java:105`.

Правила для потребителей (`AC/package-info.java:1-40`): маркер — последнее событие прогона и пишется после всех событий snapshot; normalizer обязан разбирать маркер по `type` и не проецировать его как актив; для `PARTIAL` отсутствующие поля не означают удаление.

Ожидаемая реакция журнала: `append` возвращает `JournalEntry` со статусом `RECEIVED` либо `DUPLICATE` (`ES/EventJournal.java`, Javadoc `append`); модуль статус не анализирует 🟢 (результат `inbox.record` отбрасывается: `AC/Poller.java:127, 154`, `AC/WebhookHandler.java:105`).

## 3. Raw payload в S3 (`RawPayloadStore.put(system.code(), bytes)`)

Ключ задаёт хранилище: `raw/<source>/<sha256>` (`ES/RawPayloadStore.java`, Javadoc) 🟢; `source` здесь — **код** (`eam`), не URN 🟢 (`AC/InboxWriter.java:37, 46`).

### 3.1 Raw изменения (`InboxWriter.rawJson`, `AC/InboxWriter.java:60-70`)

```json
{"sourceType":"IT_SYSTEM","sourceId":"EAM-1042","sourceVersion":3,"operation":"UPSERT","completeness":"PARTIAL","updatedAt":"2026-10-03T10:15:30Z","payload":{"name":"X","owner":null}}
```

Порядок ключей фиксирован; `sourceVersion` — число; `operation`, `completeness` — имена enum; `updatedAt` — `Instant.toString()`; `payload` — исходный объект **включая `null`** и для `DELETE` (как вернул источник; в webhook-tombstone — `{}`) 🟢. Читатель: `StoredEventReader` (`ingestion/ingestion-service/.../StoredEventReader.java:44-86`), контрактный тест `StoredEventReaderContractTest` 🟢.

### 3.2 Raw маркера (`InboxWriter.markerRawJson`, `:51-57`)

`{"syncRunId":"<id>","objectCount":<long>,"updatedAt":"<Instant>"}` — `objectCount` нужен reconciliation, чтобы не удалять объекты при неполном журнале (Javadoc `:40-43`) 🟢.

## 4. HTTP-выход: контракт источника (`HttpSourceConnector`)

Только `GET`, заголовок `Accept: application/json`, таймаут запроса 10 с (`AC/HttpSourceConnector.java:80-86`). Базовый URL очищается от хвостовых `/` (`:82`). Аутентификации к источнику нет 🟢 (в `tech-debt.md:28` ссылка на `:56-72` устарела — код формирования запроса на `:80-86`).

| Запрос | Ответ |
|---|---|
| `GET /changes?limit=<n>[&cursor=<urlenc>]` | `200` `{"changes":[{sourceType, sourceId, sourceVersion (число), operation ∈ {UPSERT,DELETE}, completeness ∈ {COMPLETE,PARTIAL}, updatedAt (ISO-8601 Instant), payload (объект)}], "nextCursor": string\|null, "hasMore": bool, "snapshotComplete": bool}` |
| `GET /objects/<type>/<id>` (оба сегмента URL-кодируются, `+` → `%20`) | `200` — одно изменение (та же форма); `404` — «не знаю» |
| `429`, `5xx`, `IOException` (timeout, обрыв) | `SourceUnavailableException(system, status or 0, Retry-After)` |
| любой другой не-`200` (кроме `404` на объект) | `IllegalStateException("<code> answered unexpected status N")` |

Отсутствующие `hasMore`/`snapshotComplete` трактуются как `false` (`Boolean.TRUE.equals`), отсутствующий/нестроковый `nextCursor` — `null`, нестандартный `payload` — `Map.of()` (`:65-67, 143`) 🟢. `limit <= 0` → `IllegalArgumentException` (`:52-54`) 🟢. Правила `Retry-After`: только целое число секунд ≥ 0, формат HTTP-date игнорируется (`:110-119`).

## 5. Checkpoint-ы (`EventJournal.loadCheckpoint/saveCheckpoint`)

См. таблицу в [`design.md`](design.md#поток-данных-общий). Формат значений: курсор источника (непрозрачный), `run:<uuid>`, `done:<uuid>`. `syncRunId` по умолчанию — `UUID.randomUUID().toString()` (`AC/Poller.java:75`) 🟢. `saveCheckpoint` — upsert без проверки монотонности (`ES/EventJournal.java`, Javadoc) 🟢.

## 6. Публичный Java-API (для `*-adapter` и `demo-launcher`)

`AdapterConfig.of(system, baseUri, secret)`; `new SourceAdapter(config, journal, rawStore)` или с собственным `SourceConnector`; `startWebhook(addr)`, `startPolling()`, `startReconciliation()`, `webhook()`, `poller()`, `connector()`, `close()`. Адаптеры `scm`, `asset`, `deploymap` не добавляют логики, `eam` подставляет составной коннектор (`_reversa_sdd/eam-adapter/`). Изменение сигнатур ломает `adapters/*-adapter` и `platform/demo-launcher` 🟢 (grep по `pom.xml`).
