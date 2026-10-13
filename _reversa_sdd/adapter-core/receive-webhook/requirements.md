# receive-webhook, Приём подписанного webhook

> Часть [`adapter-core`](../requirements.md). `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`. Реализация — [`design.md`](design.md); HTTP-таблица — [`../contracts.md`](../contracts.md) §1.1.

## Визуальная картина

Мастер-система шлёт `POST /webhook` с уведомлением («объект изменился»), подписанным HMAC-SHA256. Адаптер проверяет подпись и окно replay, разбирает уведомление, **берёт актуальное состояние у источника** (`fetchById`), записывает его в inbox (raw в S3, затем `EventJournal.append`) и только после этого отвечает `202`. Повторная доставка того же `eventId` тоже даёт `202`, но второй строки не создаёт 🟢 (`AC/WebhookHandler.java:56-107`, Javadoc `:17-30`).

## Регулярные правила

- Подпись: `WebhookSignature.verify(secret, X-Archrag-Timestamp, X-Archrag-Signature, body, now, replayWindow)`; любой результат кроме `VALID` (`MALFORMED`, `BAD_SIGNATURE`, `OUT_OF_WINDOW`) → `401` без деталей 🟢 (`AC/WebhookHandler.java:57-67`).
- Окно replay — `config.replayWindow()` (5 мин по умолчанию) в обе стороны 🟢 (`AC/AdapterConfig.java:32`; `contracts/source-spi/.../WebhookSignature.java` `verify`).
- Тело — JSON-объект, иначе `400`. Обязательны непустые строки `eventId`, `sourceType`, `sourceId`; `source` обязан равняться `system.code()` (не URN); `eventId` обязан совпасть с заголовком `X-Archrag-Event-Id`, иначе `400` 🟢 (`:68-83`).
- Дедупликация идёт по `eventId` **из подписанного тела**; заголовок подписью не защищён и только сверяется 🟢 (Javadoc `:27-28`; `:105`).
- Состояние объекта — `connector.fetchById(sourceType, sourceId)`; `SourceUnavailableException` → `503` (повторов `RetryPolicy` в этом пути нет, один вызов) 🟢 (`:84-89`).
- Источник вернул объект → события строятся из его состояния (в т. ч. `DELETE`-состояние → tombstone) 🟢 (`:91-92`).
- Источник объект не знает: при `operation == "DELETE"` в уведомлении tombstone строится по подписанному телу: `sourceVersion` обязана быть числом ≥ 0 (иначе `400`), `completeness = COMPLETE`, `updatedAt = clock.instant()`, payload пуст; при любой другой операции — `404` и ничего не пишется 🟢 (`:93-103`).
- Запись: `inbox.record(eventId, change, correlationId = eventId, syncRunId = null)`; `202` возвращается только после возврата `append` 🟢 (`:105-106`).
- Исключения журнала/S3/коннектора не ловятся обработчиком; `WebhookServer` отдаёт `500` и логирует только класс исключения 🟢 (`AC/WebhookServer.java:117-122`).
- Тело ≤ 64 КБ (`413` выше), метод только `POST` (`405`) 🟢 (`AC/WebhookServer.java:99-100, 108-115`).

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки |
|---|---|---|---|
| RF-01 | Валидный webhook → fetch → запись в inbox → `202`, событие записано до ответа | Must | `WebhookHandlerTest.validWebhookIsRecordedBeforeAccepted` |
| RF-02 | Повтор того же тела → `202`, второй строки нет | Must | `WebhookHandlerTest.repeatedWebhookIsDuplicateAndWritesNoSecondRow` |
| RF-03 | Неверная подпись → `401`, ничего не сохранено | Must | `WebhookHandlerTest.badSignatureIsRejectedAndNothingIsStored` |
| RF-04 | Timestamp вне окна → `401` | Must | `WebhookHandlerTest.timestampOutsideReplayWindowIsRejected` |
| RF-05 | Нет заголовков → `401` | Must | `WebhookHandlerTest.missingHeadersAreMalformed` |
| RF-06 | `X-Archrag-Event-Id` ≠ `eventId` тела → `400` | Must | `WebhookHandlerTest.unsignedEventIdHeaderMustAgreeWithSignedBody` |
| RF-07 | Чужой `source` или ломаное тело → `400` | Must | `WebhookHandlerTest.foreignSourceAndBrokenBodyAreBadRequests` |
| RF-08 | Источник недоступен → `503`, ничего не сохранено | Must | `WebhookHandlerTest.sourceOutageAnswersServiceUnavailableAndStoresNothing` |
| RF-09 | Источник не знает объект (upsert) → `404` | Must | `WebhookHandlerTest.unknownObjectAnswersNotFound` |
| RF-10 | Сбой журнала не маскируется `202` (исключение наружу → `500`) | Must | `WebhookHandlerTest.journalFailureIsNotAcknowledged` |
| RF-11 | Уведомление об удалении → tombstone-событие | Must | `WebhookHandlerTest.deleteNotificationRecordsTombstoneEvent` |
| RF-12 | Удаление «забытого» объекта → tombstone по подписанному телу | Must | `WebhookHandlerTest.deleteNotificationForForgottenObjectIsRecordedFromSignedBody` |
| RF-13 | `DELETE` для «забытого» объекта без валидной `sourceVersion` → `400` | Should | прямого теста нет 🟡 |
| RF-14 | Тело > 64 КБ → `413`; не-POST → `405` на `/webhook` | Should | `/webhook` через HTTP не тестируется 🟡; `405` для `/control/snapshot` — `WebhookServerControlTest.failureIs500WithoutDetailsAndGetIsRejectedWithoutRunning` |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Сравнение подписи в постоянное время; причина отказа наружу не раскрывается (`401` пустой) | `contracts/source-spi/.../WebhookSignature.java` (`MessageDigest.isEqual`), `AC/WebhookHandler.java:65-67`, `AC/WebhookServer.java:104` | 🟢 |
| Безопасность | Недоверенное тело ограничено 64 КБ и глубиной JSON 64 | `AC/WebhookServer.java:30, 111-115`, `AC/Json.java:18` | 🟢 |
| Безопасность | Секрет не попадает в логи/исключения | `AC/AdapterConfig.java:73`, `AC/WebhookServer.java:120` | 🟢 |
| Производительность | Обработка на виртуальных потоках: медленный `fetchById` не блокирует остальные | `AC/WebhookServer.java:52-53` | 🟢 |
| Надёжность | `202` только после записи; источник повторит доставку при `500`/`503` | `AC/WebhookHandler.java:105-106`, Javadoc `:17-25` | 🟢 |

## Критерии приёмки

```gherkin
# Позитив
Дано источник знает объект IT_SYSTEM/EAM-1 версии 3, секрет S, валидные заголовки и тело {eventId:E, source:"eam", sourceType, sourceId}
Когда WebhookHandler.handle(header, body)
Тогда 202, в журнале строка (urn:corp:eam, E) со статусом RECEIVED, непустым payloadRef, raw-объект лежит в хранилище
  И по коду correlationid=E, sync_run_id=null, raw пишется до append (тест проверяет статус, sourceId, payloadRef и наличие raw)
                                                                    # WebhookHandlerTest.validWebhookIsRecordedBeforeAccepted

# Позитив: повтор
Дано тот же запрос доставлен повторно
Когда handle(...)
Тогда 202, новых строк в журнале нет (DUPLICATE)                     # WebhookHandlerTest.repeatedWebhookIsDuplicateAndWritesNoSecondRow

# Негатив: подпись
Дано подпись, не совпадающая с HMAC(secret, timestamp+"."+body)
Когда handle(...)
Тогда 401 и вызова fetchById/записи нет                              # WebhookHandlerTest.badSignatureIsRejectedAndNothingIsStored

# Негатив: replay
Дано корректная подпись, timestamp старше окна
Когда handle(...)
Тогда 401                                                            # WebhookHandlerTest.timestampOutsideReplayWindowIsRejected

# Негатив: подмена заголовка
Дано X-Archrag-Event-Id отличается от eventId подписанного тела
Когда handle(...)
Тогда 400                                                            # WebhookHandlerTest.unsignedEventIdHeaderMustAgreeWithSignedBody

# Негатив: источник недоступен / не знает
Дано fetchById бросает SourceUnavailableException  → 503, ничего не записано   # WebhookHandlerTest.sourceOutageAnswersServiceUnavailableAndStoresNothing
Дано fetchById пуст, operation != DELETE          → 404, ничего не записано   # WebhookHandlerTest.unknownObjectAnswersNotFound

# Негатив: журнал
Дано EventJournal.append бросает исключение
Когда handle(...)
Тогда исключение выходит наружу (WebhookServer отдаст 500), 202 не возвращён   # WebhookHandlerTest.journalFailureIsNotAcknowledged

# Tombstone по подписанному телу
Дано fetchById пуст, operation="DELETE", sourceVersion=5
Когда handle(...)
Тогда 202 и событие architecture.asset.deleted.v1 с версией "5"; повторная доставка — 202 без второй строки    # WebhookHandlerTest.deleteNotificationForForgottenObjectIsRecordedFromSignedBody

# Негатив: tombstone без версии
Дано fetchById пуст, operation="DELETE", sourceVersion отсутствует или -1
Когда handle(...)
Тогда 400                                                            # прямого теста нет 🟡
```

## Приоритет

Must: RF-01…RF-12 (канал низкой задержки, ADR 0014; безопасность входа). Should: RF-13, RF-14. Webhook-путь EAM в реальности не используется (в реальном EAM webhook'ов нет), но сервер для него запускается 🟡 (`_reversa_sdd/code-analysis.md` §P2.3; `docs/06-eam-integration.md:64`).

## Трассировка

| Файл | Символ | Тест | Покрытие |
|---|---|---|---|
| `AC/WebhookHandler.java:56-111` | `handle`, `text` | `WebhookHandlerTest` (12) | 🟢 |
| `AC/WebhookServer.java:96-123` | `serve`, `process` | косвенно; 405/413/500 `/webhook` не тестируются | 🟡 |
| `contracts/source-spi/.../WebhookSignature.java` | `verify` | тесты в `contracts/source-spi` | 🟢 |
