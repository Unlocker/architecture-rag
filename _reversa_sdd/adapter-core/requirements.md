# adapters/adapter-core

> Writer, R.6a. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел/расхождение. Сокращения путей:
> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`,
> `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`.
> Номера строк сверены с кодом при написании (не взяты из R.2b). Проектирование — [`design.md`](design.md), задачи — [`tasks.md`](tasks.md), внешние форматы — [`contracts.md`](contracts.md).

## Визуальная картина

Общее ядро всех адаптеров мастер-систем. Читает источник по REST (`GET /changes`, `GET /objects/{type}/{id}`), принимает подписанный webhook, превращает `SourceChange` в `CanonicalEvent` и фиксирует его в inbox: сначала raw payload в объектное хранилище, затем `EventJournal.append`. Ничего не пишет в источник, Neo4j-доступа и source DTO за пределами адаптера нет 🟢 (`AC/package-info.java:1-5`, `AC/SourceAdapter.java:12-16`, `AC/HttpSourceConnector.java:25`, `adapters/adapter-core/pom.xml:17-32`). Конкретные адаптеры (`eam`, `scm`, `asset`, `deploymap`) только выбирают `SourceSystem` и конфигурацию.

## Responsabilidades (Ответственность)

- Инкрементальный polling по непрозрачному курсору с повторами и backoff 🟢 (`AC/Poller.java:89-96, 120-145, 191-204`).
- Полный snapshot под одним `syncRunId` с маркером `snapshot-complete` и возобновлением прерванного прогона 🟢 (`AC/Poller.java:147-188`).
- Reconciliation: периодический и ручной полный snapshot при существующем курсоре 🟢 (`AC/Poller.java:103-105`, `AC/SourceAdapter.java:97-115`).
- Приём HMAC-подписанного webhook и ответ статусом HTTP 🟢 (`AC/WebhookHandler.java:56-107`, `AC/WebhookServer.java:96-123`).
- Ручной запуск snapshot `POST /control/snapshot` 🟢 (`AC/WebhookServer.java:67-94`).
- Маппинг `SourceChange` → `CanonicalEvent`, детерминированные `eventId` 🟢 (`AC/EventMapper.java:52-109`).
- Запись в inbox: raw в S3, затем журнал; ошибки не глушатся 🟢 (`AC/InboxWriter.java:34-48`).
- REST-коннектор по умолчанию `HttpSourceConnector` 🟢 (`AC/HttpSourceConnector.java:33-157`).
- Самописный JSON-парсер/писатель без внешних зависимостей 🟢 (`AC/Json.java:16-277`).

## Регулярные правила (Бизнес-правила)

1. `source` события, ключа дедупликации и checkpoint = `urn:corp:<system.code()>` 🟢 (`AC/EventMapper.java:47-49`). Коды: `eam`, `scm`, `cmdb`, `deploymap` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java:5-9`).
2. Значения `null` верхнего уровня `payload` в событие не попадают: «поле не передано», не «стёрто» (ловушка 5) 🟢 (`AC/EventMapper.java:55-61`). Вложенные `null` не фильтруются 🟡 (код фильтрует только `forEach` верхнего уровня). В raw JSON в S3 `null` сохраняются как есть 🟢 (`AC/InboxWriter.java:68`).
3. Полнота записи — зарезервированный ключ `_completeness` ∈ {`COMPLETE`, `PARTIAL`} внутри `data.payload` 🟢 (`AC/EventMapper.java:35, 62`).
4. `DELETE` → событие `architecture.asset.deleted.v1` с пустым payload (без `_completeness`) 🟢 (`AC/EventMapper.java:53-63, 66`).
5. Идентификаторы событий детерминированы: polling `poll:<type>/<id>/v<ver>`, snapshot `snap:<runId>:<type>/<id>/v<ver>`, маркер `snapshot-complete:<runId>`, webhook — `eventId` источника 🟢 (`AC/EventMapper.java:81-83, 98-105`, `AC/WebhookHandler.java:105`). Перечитывание после сбоя даёт `DUPLICATE`, а не вторую строку 🟡 (дедупликация `source + eventId` — на стороне `EventJournal`, `contracts/event-schemas/.../EventJournal.java`, Javadoc `append`).
6. Курсор polling сдвигается только после `append` всей страницы (ловушка 6) 🟢 (`AC/Poller.java:126-139`). Сдвиг — после записи в **журнал**, а не после проекции в Neo4j 🟢; это расходится с формулировкой ADR 0020 → 🔴 R-02.
7. Missing set разрешён только после маркера `snapshot-complete`; без подтверждения полноты источником маркер не пишется (ловушка 9) 🟢 (`AC/Poller.java:164-167`).
8. Прерванный snapshot продолжается под тем же `syncRunId`; после завершения следующий получает новый 🟢 (`AC/Poller.java:180-188`; тесты `PollerTest.restartedSnapshotReusesRunIdAndRecordsDuplicatesInTheSameRun`, `secondSnapshotGetsNewRunIdAfterCompletion`).
9. Циклы одного источника не пересекаются (внутрипроцессный `ReentrantLock`); занятый замок → `ALREADY_RUNNING`, чтение источника не начинается 🟢 (`AC/Poller.java:50, 107-118`). Рассчитано на один экземпляр адаптера (`AC/Poller.java:34-35`).
10. Недоступность источника (429/5xx/timeout) повторяется по `RetryPolicy`; исчерпание попыток → `GAVE_UP` без изменения checkpoint 🟢 (`AC/Poller.java:191-204, 112-114`).
11. Ошибки журнала и S3 пробрасываются (ловушка 14); webhook не отдаёт `202` до записи в inbox 🟢 (`AC/InboxWriter.java:19`, `AC/WebhookHandler.java:105-106`, `AC/WebhookServer.java:119-121`).
12. Недоверенное содержимое источника не попадает в логи и тексты исключений (ловушка 11): логируется только класс исключения 🟢 (`AC/SourceAdapter.java:83-85, 108-110`, `AC/WebhookServer.java:85, 120`, `AC/HttpSourceConnector.java:106, 125, 132, 145`).
13. Секрет webhook не попадает в `toString` конфигурации (ловушка 12) 🟢 (`AC/AdapterConfig.java:71-75`; тест `RetryPolicyTest.configToStringHidesWebhookSecret`).

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки |
|---|---|---|---|
| RF-01 | `AdapterConfig` отвергает пустой/пробельный `webhookSecret`, неположительные `replayWindow`, `pageSize`, `pollInterval`, `reconcileInterval`, `maxAttempts`, `baseDelay`; `maxDelay < baseDelay` (`IllegalArgumentException`) 🟢 | Must | `RetryPolicyTest.rejectsInvalidParameters`; для `AdapterConfig` прямого теста нет 🟡 |
| RF-02 | `AdapterConfig.of(system, uri, secret)` задаёт: replayWindow 5 мин, pageSize 100, pollInterval 30 с, reconcileInterval 6 ч, `RetryPolicy.defaults()` 🟢 (`AC/AdapterConfig.java:32, 57, 60-63`) | Must | см. АС-09 |
| RF-03 | Первый `pollOnce` (нет checkpoint `<code>-poller`) выполняет полный snapshot; дальше — инкрементальное чтение 🟢 (`AC/Poller.java:89-96`) | Must | [`full-snapshot`](full-snapshot/requirements.md), [`incremental-poll`](incremental-poll/requirements.md) |
| RF-04 | `snapshotOnce` выполняет полный snapshot независимо от курсора (reconciliation) 🟢 (`AC/Poller.java:103-105`) | Must | `PollerTest.snapshotOnceRunsFullSnapshotWithNewRunIdEvenWhenCursorExists` |
| RF-05 | Webhook: подпись → разбор → `fetchById` → inbox → статус 🟢 | Must | [`receive-webhook`](receive-webhook/requirements.md) |
| RF-06 | `POST /control/snapshot` синхронно запускает `snapshotOnce` и отвечает JSON `{"outcome","appended","syncRunId"}` 🟢 | Should | `WebhookServerControlTest.*` |
| RF-07 | `HttpSourceConnector` реализует `SourceConnector` поверх REST и переводит 429/5xx/сетевой сбой в `SourceUnavailableException` 🟢 | Must | [`source-fetch-retry`](source-fetch-retry/requirements.md) |
| RF-08 | `SourceAdapter` собирает коннектор, `WebhookHandler`, `Poller`; запускает webhook-сервер, polling и reconciliation; `close()` останавливает всё и закрывает `AutoCloseable`-коннектор 🟢 (`AC/SourceAdapter.java:29-136`) | Must | `SourceAdapterTest.adapterReadsThroughTheGivenConnectorAndClosesIt` |
| RF-09 | Сбой цикла polling/reconciliation (`RuntimeException`) логируется типом и не останавливает расписание 🟢 (`AC/SourceAdapter.java:82-86, 107-111`) | Should | Прямого теста нет 🟡 (TT-09) |
| RF-10 | `EventMapper` строит события `upserted`/`deleted`/`snapshot-complete` в формате [`contracts.md`](contracts.md) §2 🟢 | Must | `PollerTest.deleteBecomesTombstoneEventWithEmptyPayload`, `partialRecordWithNullsDoesNotCrashAndKeepsNullsOutOfPayload`, `firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects` |
| RF-11 | Raw JSON в S3 читается `StoredEventReader` (ingestion) без потерь формата 🟢 | Must | `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/adaptercore/StoredEventReaderContractTest.java` |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Webhook без валидной подписи/timestamp получает `401` с пустым телом (причина не раскрывается) | `AC/WebhookHandler.java:65-67`, `AC/WebhookServer.java:104` | 🟢 |
| Безопасность | Сравнение подписи — в постоянное время (`MessageDigest.isEqual`), replay window в обе стороны | `contracts/source-spi/.../WebhookSignature.java` (метод `verify`) | 🟢 |
| Безопасность | Размер тела webhook ограничен 64 КБ (`MAX_BODY_BYTES = 64 * 1024`), читается `readNBytes(MAX+1)`, `413` | `AC/WebhookServer.java:30, 108-115` | 🟢 |
| Безопасность | `/control/snapshot` **не аутентифицирован**; защита только сетевая | `AC/WebhookServer.java:18-22` (Javadoc), `docs/03-interfaces.md:33, 560` | 🟢 (риск принят) |
| Безопасность | Секрет webhook маскируется в `toString` | `AC/AdapterConfig.java:73` | 🟢 |
| Безопасность | JSON источника: глубина вложенности ≤ 64 (`MAX_DEPTH`) | `AC/Json.java:18, 48-50` | 🟢 |
| Производительность | Таймауты HTTP-клиента источника: connect 5 с, запрос 10 с | `AC/HttpSourceConnector.java:38, 83` | 🟢 |
| Производительность | Webhook обрабатывается на виртуальных потоках (`newVirtualThreadPerTaskExecutor`), медленный `fetchById` не блокирует остальные | `AC/WebhookServer.java:52-53` | 🟢 |
| Производительность | Расписания polling/reconciliation — однопоточные `ScheduledExecutorService` на виртуальных потоках, `scheduleWithFixedDelay` | `AC/SourceAdapter.java:76-77, 101-102` | 🟢 |
| Доступность | Backoff: до 5 попыток, 500 мс…30 с, equal jitter, `Retry-After` учитывается, но ограничен `maxDelay` | `AC/RetryPolicy.java:31-33, 40-50` | 🟢 |
| Доступность | Источник «зациклил» (`hasMore` без нового курсора) — `IllegalStateException` вместо бесконечного чтения | `AC/Poller.java:130-133, 158-160` | 🟢 |
| Масштабирование | Один экземпляр адаптера на источник; многоэкземплярный режим не поддержан | `AC/Poller.java:34-35, 50` | 🟢 (ограничение) |

> Нефункциональных требований по метрикам/трейсингу в модуле нет: Micrometer/OpenTelemetry не используются (grep по `AC/*.java` пуст) 🟢 — строки не пишутся.

## Критерии приёмки (уровень модуля)

Сценарные критерии — в папках use case. Здесь — межсценарные.

```gherkin
# АС-01 (позитив): конфигурация по умолчанию
Дано AdapterConfig.of(SourceSystem.EAM, URI, "секрет")
Когда читаются поля
Тогда replayWindow = 5 мин, pageSize = 100, pollInterval = 30 с, reconcileInterval = 6 ч, retry = 5 попыток 500 мс…30 с

# АС-02 (негатив): пустой секрет
Дано webhookSecret = "   "
Когда создаётся AdapterConfig
Тогда IllegalArgumentException("webhookSecret is required")          # теста нет 🟡

# АС-03 (негатив): секрет не утекает
Дано AdapterConfig с секретом S
Когда вызывается toString()
Тогда в строке нет S, есть "webhookSecret=***"                       # RetryPolicyTest.configToStringHidesWebhookSecret

# АС-04: события детерминированы
Дано одно и то же SourceChange (sourceType T, sourceId I, версия 3), прочитанное polling дважды
Когда обе записи пишутся в журнал
Тогда eventId = "poll:T/I/v3" оба раза, вторая запись — DUPLICATE   # формат id: PollerTest.deleteBecomesTombstoneEventWithEmptyPayload (poll:IT_SYSTEM/EAM-2001/v2); DUPLICATE: WebhookHandlerTest.repeatedWebhookIsDuplicateAndWritesNoSecondRow (для polling прямого теста нет 🟡)

# АС-05: null не стирает поле
Дано SourceChange PARTIAL с payload {a:1, b:null}
Когда строится CanonicalEvent
Тогда data.payload = {a:1, _completeness:"PARTIAL"}                  # PollerTest.partialRecordWithNullsDoesNotCrashAndKeepsNullsOutOfPayload

# АС-06: журнал недоступен — checkpoint цел
Дано EventJournal.append бросает IllegalStateException
Когда выполняется pollOnce
Тогда исключение пробрасывается и checkpoint не меняется             # PollerTest.journalFailureDoesNotMoveCheckpoint

# АС-07: второй цикл не стартует
Дано идёт цикл Poller
Когда другой поток вызывает snapshotOnce()
Тогда он получает ALREADY_RUNNING и не читает источник               # PollerTest.concurrentCycleOfTheSameSourceIsRejected

# АС-08: чужой Retry-After не усыпляет poller
Дано источник прислал Retry-After 999 999 999 с, maxDelay = 8 с (политика теста)
Когда считается пауза
Тогда пауза = maxDelay (8 с)                                                   # RetryPolicyTest.hugeRetryAfterIsCappedByMaxDelay

# АС-09 (негатив): неположительные параметры
Дано RetryPolicy(0, …) или baseDelay = 0 или maxDelay < baseDelay
Когда создаётся RetryPolicy
Тогда IllegalArgumentException                                       # RetryPolicyTest.rejectsInvalidParameters
```

## Приоритет (MoSCoW)

| Требование | MoSCoW | Обоснование |
|---|---|---|
| Запись в inbox (raw → journal), `EventMapper`, формат событий | Must | Единственная точка входа данных в платформу; контракт с `normalizer`/`graph-projector` |
| `Poller` (incremental, snapshot, замок, retry) | Must | Основной поставщик данных; корректность удалений (ловушка 9) |
| `WebhookHandler` + `WebhookServer /webhook` | Must | Низкая задержка (ADR 0014) |
| `HttpSourceConnector`, `RetryPolicy`, `Json` | Must | Нужны всем адаптерам по умолчанию |
| `SourceAdapter` расписания | Must | Без них polling не работает в `demo-launcher` |
| `POST /control/snapshot` | Should | Ручной reconcile (E1.7); есть автоматическое расписание |
| `Sleeper` как точка подмены | Could | Только тесты |
| Многоэкземплярный замок (advisory lock в журнале) | Won't (PoC) | Прямо отложено в Javadoc `AC/Poller.java:34-35` |

## Рассогласования и пробелы

- 🔴 R-01 — литералы типов `deleted`/`snapshot-complete` продублированы в ingestion; теста на совпадение нет. См. [`questions.md`](questions.md).
- 🟢 [R.8: решение владельца, вопрос 4; UNLOCKER-283] R-02 — курсор адаптера после `append` в журнал намеренно отделён от checkpoint `projector` (раздел «Уточнение 2026-10-14» в ADR 0020).
- 🔴 R-03 — «отравленный» объект (например, отрицательная `sourceVersion`) вызывает исключение в `EventMapper`, и цикл падает на нём вечно; карантина на уровне адаптера нет.
- Остальное — в [`questions.md`](questions.md).

## Rastreabilidad de Código (Трассировка)

| Файл | Класс | Тесты | Покрытие |
|---|---|---|---|
| `AC/SourceAdapter.java` | `SourceAdapter` | `SourceAdapterTest` (2) | 🟡 расписания не покрыты |
| `AC/Poller.java` | `Poller` | `PollerTest` (15) | 🟢 |
| `AC/EventMapper.java` | `EventMapper` | косвенно `PollerTest`, `WebhookHandlerTest`; `StoredEventReaderContractTest` | 🟢 |
| `AC/InboxWriter.java` | `InboxWriter` | косвенно; `StoredEventReaderContractTest` (в `ingestion-service`) | 🟢 |
| `AC/WebhookHandler.java` | `WebhookHandler` | `WebhookHandlerTest` (12) | 🟢 |
| `AC/WebhookServer.java` | `WebhookServer` | `WebhookServerControlTest` (4); `/webhook` через HTTP — только в интеграционных тестах других модулей 🟡 | 🟡 |
| `AC/HttpSourceConnector.java` | `HttpSourceConnector` | `HttpSourceConnectorTest` (6) | 🟢 |
| `AC/RetryPolicy.java` | `RetryPolicy` | `RetryPolicyTest` (6, из них 1 про `AdapterConfig`) | 🟢 |
| `AC/AdapterConfig.java` | `AdapterConfig` | косвенно | 🟡 |
| `AC/Json.java` | `Json` | `JsonTest` (5) | 🟡 числа не покрыты |
| `AC/PollResult.java`, `AC/Sleeper.java`, `AC/package-info.java` | — | — | 🟢 |
