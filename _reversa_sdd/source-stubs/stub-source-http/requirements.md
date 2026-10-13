# stub-source-http: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Wire-формат: [`../contracts.md`](../contracts.md) §1. Журнал за фасадом: [`../stub-source-journal/requirements.md`](../stub-source-journal/requirements.md).

## Visão Geral

`StubSourceServer` оборачивает `StubSource` в HTTP-API на JDK `HttpServer`: `GET /changes?cursor=&limit=` и `GET /objects/{type}/{id}`. Это сетевой слой, по которому REST-адаптеры (`eam`, `scm`, `asset`, `deploymap`) читают заглушки так же, как читали бы реальный источник. Дополнительно сервер умеет отвечать сбоем на вызов с заданным номером (`failOnCall`) и пробрасывает сбои, включённые в `StubSource.failNext`. Только чтение 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:22-30`).

## Regras de Negócio

- Два контекста: `/changes` и `/objects/`; запрос на любой иной путь отвергается самим `HttpServer` (`404` без тела заглушки) 🟡 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:53-54`).
- Не-`GET` → `405 {}`, без изменения счётчика вызовов и без обращения к источнику 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:107-108`).
- `GET`: сначала `failIfScheduled()` (увеличивает общий счётчик вызовов `calls` и, если для этого номера запланирован сбой, бросает и удаляет его), затем обработчик 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:109-111, 129-134`).
- `/changes`: `cursor` пустой или отсутствует → `null` (начало snapshot); `limit` по умолчанию `100`; `limit` не число или ≤ 0 → `400 {}` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:33, 79-88`).
- `/objects/{type}/{id}`: ровно два сегмента, оба URL-декодируются; иное число сегментов или неизвестный объект → `404 {}`; объект с последней записью `DELETE` отдаётся `200` с `operation=DELETE` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:90-100`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:170-180`).
- `SourceUnavailableException` → HTTP-статус исключения, `0` → `504`; `Retry-After` в целых секундах, если задан 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:113-117`).
- `IllegalArgumentException` (включая `NumberFormatException`, неизвестный префикс курсора, `limit ≤ 0`) → `400 {}` без эха значения 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:118-120`).
- Ответ: `Content-Type: application/json`, длина тела известна, тело закрывается вместе с обменом (`try (ex)`) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:103, 121-126`).
- 🔴 Необработанное исключение (курсор `s5` без `:`) вылетает из обработчика `HttpServer`: клиент получает обрыв соединения, а не `400 {}` — Q-01.
- 🟡 `baseUri()` всегда `http://127.0.0.1:<port>`, даже при привязке к `0.0.0.0` — Q-11.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Обход `/changes` по `nextCursor` до `snapshotComplete` | Must | AC-01 |
| RF-02 | `/objects/{type}/{id}`: `200`/`404` | Must | AC-02 |
| RF-03 | Валидация `limit` → `400` | Must | AC-03 |
| RF-04 | Сбои: расписание по номеру вызова, `Retry-After`, `504` на timeout | Must | AC-04 |
| RF-05 | Только чтение (`405`) | Must | AC-05 |
| RF-06 | Явный адрес для контейнера, порт 0 по умолчанию | Should | AC-06 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Segurança | Тела ошибок всегда `{}`; ввод не эхуется | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:94, 98, 108, 114, 119` | 🟢 |
| Segurança | Аутентификации нет; защита — сетевая граница, публиковать наружу нельзя | `docs/03-interfaces.md:560` | 🟢 |
| Confiabilidade | Счётчик вызовов `synchronized`, расписание в `ConcurrentHashMap` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:37-39, 129-134` | 🟢 |
| Desempenho | Executor не задан: один поток обработки | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:49, 55` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01: обход страниц (StubSourceServerTest.pagesAreWalkedByCursor)
Дано StubSources.eam(clock) за сервером
Когда GET /changes?limit=2, затем GET /changes?limit=2&cursor=<nextCursor первой страницы, URL-кодированный>
Тогда первая страница: "hasMore":true, "snapshotComplete":false; вторая: "hasMore":false, "snapshotComplete":true, содержит "sourceId":"EAM-1042" и не содержит "sourceId":"TEAM-PAY"

# AC-02: объект и 404 (StubSourceServerTest.objectSnapshotAndNotFound)
Дано объект IT_SYSTEM/EAM-1042 версии 1
Когда GET /objects/IT_SYSTEM/EAM-1042
Тогда 200, тело содержит "sourceVersion":1 и "name":"Payments Core"
Когда GET /objects/IT_SYSTEM/nope
Тогда 404

# AC-03: неверный limit (StubSourceServerTest.invalidLimitIsBadRequest)
Когда GET /changes?limit=0 либо ?limit=abc
Тогда 400

# AC-04: сбои (StubSourceServerTest.rateLimitCarriesRetryAfterOnScheduledCall, serverErrorFromSourceFailureIsPropagated, timeoutIsReportedAsGatewayTimeout)
Дано failOnCall(2, 429, 7 с)
Когда три вызова GET /changes подряд
Тогда 200, затем 429 с заголовком Retry-After: 7, затем 200
Дано source.failNext(1, 503, null)
Когда GET /objects/IT_SYSTEM/EAM-1042
Тогда 503 без Retry-After; повторный вызов — 200
Дано failOnCall(1, 0, null)
Когда GET /changes
Тогда 504

# AC-05: запись отвергается (StubSourceServerTest.writesAreRejected)
Когда POST /changes
Тогда 405

# AC-06: явный адрес (StubSourceServerTest.explicitAddressOverloadServesOnChosenPort)
Дано второй сервер на loopback:0
Когда GET /changes?limit=1
Тогда 200 и port() > 0

# AC-07 (отказ, не покрыто тестом) 🔴
Дано курсор s5
Когда GET /changes?cursor=s5
Тогда ожидается 400 {} (docs/03-interfaces.md:531), фактически обрыв соединения (Q-01)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Адаптеры читают источники только по HTTP |
| RF-06 | Should | Нужно `demo-launcher` для контейнера |
| Единый перехват `RuntimeException` → 400 | Won't | Не реализовано (Q-01) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java` | `StubSourceServer` | 🟢 |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceServerTest.java` | 8 тестов | 🟢 |
