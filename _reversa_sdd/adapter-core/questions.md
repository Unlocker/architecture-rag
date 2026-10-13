# adapters/adapter-core, Открытые вопросы

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Исходники в рамках R.6a не правятся. Адресат 🔴 — архитектор / владелец.

## 🔴 Требуют решения человека

| ID | Вопрос | Evidence | Что предлагается решить |
|---|---|---|---|
| R-01 | Литералы типов событий `architecture.asset.deleted.v1` и `architecture.sync.snapshot-complete.v1` (и схема `asset-deleted:1`) объявлены в адаптере и скопированы в `EventProcessor`, `StoredEventReader`, `ReplayService`; в контракте `CanonicalEvent` есть только `TYPE_ASSET_UPSERTED`. Теста на совпадение нет, переименование молча разведёт типы (событие уйдёт в `UNSUPPORTED_EVENT_TYPE`). | `AC/EventMapper.java:29-32, 40-42`; `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEvent.java` (константа только upsert); `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:92-94`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:34`; `_reversa_sdd/traceability/spec-impact-matrix.md:34`, `_reversa_sdd/tech-debt.md:60` | Вынести константы в `contracts/event-schemas` либо добавить контрактный тест на равенство литералов |
| R-02 | Расхождение с ADR 0020: «High-water mark двигается после успешной проекции страницы». Курсор poller'а сдвигается после `append` страницы в журнал, не после проекции в Neo4j; после коммита Neo4j двигается отдельный checkpoint `projector`. | `AC/Poller.java:22-25, 126-139`; `docs/adr/0020-tombstone-instead-of-hard-delete.md` («Решение»); `_reversa_sdd/adrs/retroactive-map.md:63` | Уточнить формулировку ADR 0020 (два water mark, разделённых журналом, ADR 0006) или принять как осознанное |
| R-03 | «Отравленный» объект в странице не имеет политики карантина на уровне адаптера. Пример: `sourceVersion < 0` в ответе источника → `new SourceVersion("-5")` бросает `IllegalArgumentException` в `EventMapper.toEvent`, цикл падает, checkpoint не сдвигается, каждый следующий цикл повторяет падение на том же объекте — poller застревает навсегда. Аналогично `IllegalStateException` «malformed change/JSON» из `HttpSourceConnector` обрывает весь цикл. В `SourceAdapter` это видно только как ERROR `poll failed: <класс>`. | `AC/EventMapper.java:75`; `contracts/event-schemas/.../SourceVersion.java` (`[0-9]{1,40}`); `AC/HttpSourceConnector.java:129-147`; `AC/Poller.java:126-129`; `AC/SourceAdapter.java:82-85` | Решить: пропускать/кварантинить такой объект (запись в DLQ/лог с кодом) или считать это ошибкой источника и оставить как есть. Правило CLAUDE.md №14 требует `QUARANTINED`/`RETRYING` для обработки событий, но для чтения источника не определено |

## 🟡 Выведено / риск принят / нет теста (не блокирует)

| ID | Утверждение | Evidence |
|---|---|---|
| U-01 | `incremental` возвращает `UP_TO_DATE` и при `appended > 0`, если курсор не изменился | `AC/Poller.java:134-142` |
| U-02 | Webhook не сверяет `sourceType`/`sourceId` объекта из `fetchById` с уведомлением | `AC/WebhookHandler.java:91-92, 105` |
| U-03 | Повторный `startWebhook` игнорирует новый адрес | `AC/SourceAdapter.java:61-66` |
| U-04 | Целое вне диапазона `long` в JSON источника → `bad number` → весь ответ `IllegalStateException("… malformed JSON")` (а не `Double`, как сказано в `code-analysis.md:259`) | `AC/Json.java:160-174`, `AC/HttpSourceConnector.java:121-127` |
| U-05 | Prefix-соответствие JDK `HttpContext`: `/webhook/…`, `/control/snapshot…` тоже попадут в обработчик | `AC/WebhookServer.java:54-57` |
| U-06 | Порядок ключей JSON-ответа `/control/snapshot` не гарантирован (`Map.of`) | `AC/WebhookServer.java:82-83` |
| U-07 | Повторы webhook-tombstone пишут в S3 разные объекты (разный `updatedAt`) | `AC/WebhookHandler.java:99-100`, `AC/InboxWriter.java:67` |
| U-08 | Устаревший Javadoc `@param port` у конструктора `WebhookServer` | `AC/WebhookServer.java:37-38` |
| U-09 | `/control/snapshot` без аутентификации — документированное ограничение PoC (сеть compose) | `AC/WebhookServer.java:18-22`, `docs/03-interfaces.md:33, 560` |
| U-10 | Замок внутрипроцессный; несколько экземпляров адаптера дадут параллельные snapshot'ы | `AC/Poller.java:34-35, 50` |
| U-11 | `Reconciliation`-запуск при занятом замке молча пропускается (лог `INFO … reconcile ALREADY_RUNNING`), следующий — через `reconcileInterval` (6 ч) | `AC/SourceAdapter.java:105-106`, `AC/Poller.java:108-110` |
| U-12 | `snapshot` по завершении безусловно перезаписывает курсор polling значением `nextCursor` последней страницы snapshot, даже если курсор был дальше (изменения между началом и концом snapshot полагаются на идемпотентность) | `AC/Poller.java:171` |
| U-13 | Один и тот же `sourceVersion` у `poll:T/1/v3` и `snap:run:T/1/v3`: корректность зависит от того, что projector не считает равную версию «старой» | `AC/EventMapper.java:98-105` |
| U-14 | `long` против `SourceVersion` (до 40 цифр): версия > `Long.MAX_VALUE` не выразима | `contracts/source-spi/.../SourceChange.java` (`long sourceVersion`), `ES/SourceVersion.java` |
| U-15 | `AdapterConfig`: `replayWindow`/`pollInterval` равны `null` → `NullPointerException` без имени поля; пустой `webhookSecret` отвергается, но теста нет; `toString` не показывает `pollInterval`, `reconcileInterval`, `retry` | `AC/AdapterConfig.java:34-55, 71-75` |
| U-16 | Тест-пробелы: `413` и `405` на `/webhook` через HTTP, `startPolling`/`startReconciliation`, значение `_completeness = COMPLETE`, вложенные `null`, числа в `Json`, `HttpSourceConnector` на «неожиданный статус»/«malformed change» | поиск по `AT/*.java` |

## Найденные ошибки в предыдущих документах

| Документ | Ошибка | Правильно (по коду) |
|---|---|---|
| `_reversa_sdd/traceability/spec-impact-matrix.md:39` (§2 №6) | Формат raw JSON приписан `EventMapper.java:52-95` | `AC/InboxWriter.java:51-70` (`markerRawJson`, `rawJson`); `EventMapper.java:52-95` — это маппинг `CanonicalEvent` |
| `_reversa_sdd/traceability/spec-impact-matrix.md:35` (§2 №2) | «Таблица кодов источников» привязана к `EventMapper.java:46-48` | `EventMapper.java:47-49` только строит URN из `system.code()`; таблица кодов — `contracts/source-spi/.../SourceSystem.java:5-9` |
| `_reversa_sdd/code-analysis.md:259` (§P1.5) | «большие целые > 2^63 станут `Double`» | `Json.number` пропускает токен из цифр через `Long.parseLong`; при переполнении `NumberFormatException` → `bad number` (`AC/Json.java:168-173`) |
| `_reversa_sdd/tech-debt.md:28` (3.5) | «нет аутентификации: `HttpSourceConnector.java:56-72`» | строки 56-72 — разбор ответа; построение запроса без заголовков авторизации — `HttpSourceConnector.java:80-86` |
| `_reversa_sdd/traceability/spec-impact-matrix.md:40` (§2 №7) | Сторона B: `InboxWriter.java:34-48` (метод `record`) | формат `DELETE`-raw задаёт `rawJson`, `InboxWriter.java:60-70`; `record` лишь вызывает его (`:37`) |
