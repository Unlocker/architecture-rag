# incremental-poll, Инкрементальное чтение источника

> Часть [`adapter-core`](../requirements.md). `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`. Реализация — [`design.md`](design.md).

## Визуальная картина

Один цикл `Poller.pollOnce()` при уже существующем курсоре: читает страницы изменений после курсора, пишет каждое изменение в inbox и после записи **всей** страницы сдвигает курсор. Вызывается расписанием `SourceAdapter.startPolling` (каждые `pollInterval`, по умолчанию 30 с) 🟢 (`AC/Poller.java:89-96, 120-145`, `AC/SourceAdapter.java:72-90`).

## Регулярные правила

- Курсор хранится в checkpoint (`consumer = <code>-poller`, `source = urn:corp:<code>`) 🟢 (`AC/Poller.java:79-81, 93`).
- `eventId` изменения = `poll:<sourceType>/<sourceId>/v<sourceVersion>`; `correlationId = null`; `sync_run_id = null` 🟢 (`AC/Poller.java:127`, `AC/EventMapper.java:98-100`).
- Курсор сдвигается только после записи всех изменений страницы и только если `nextCursor != null` и `!= cursor` 🟢 (`AC/Poller.java:134-139`).
- Страница с `hasMore = true`, у которой `nextCursor` пуст или равен текущему → `IllegalStateException("<code> reported hasMore without advancing the cursor")`, цикл не продолжается (защита от зацикливания недоверенным источником) 🟢 (`AC/Poller.java:130-133, 176-178`).
- Исход: `ADVANCED`, если курсор хотя бы раз сдвигался; иначе `UP_TO_DATE`; `appended` считает и дубликаты 🟢 (`AC/Poller.java:140-143`). `UP_TO_DATE` возможен и при `appended > 0`, если курсор не изменился 🟡 (U-01).
- При недоступности источника — повторы [`source-fetch-retry`](../source-fetch-retry/requirements.md); после исчерпания `GAVE_UP` с `appended`, накопленным в цикле, и `syncRunId = null` 🟢 (`AC/Poller.java:112-114, 125, 203`).
- Ошибки журнала/S3 (`append`, `put`, `saveCheckpoint`) пробрасываются; checkpoint остаётся прежним 🟢.
- Если замок занят — `ALREADY_RUNNING` (0, `null`), источник не читается 🟢 (`AC/Poller.java:107-110`).

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки |
|---|---|---|---|
| RF-01 | Читать страницы от сохранённого курсора с `limit = config.pageSize()` | Must | `PollerTest.incrementalPollingAdvancesCursorAfterEachPage` |
| RF-02 | Писать изменения в inbox до сдвига курсора | Must | `PollerTest.journalFailureDoesNotMoveCheckpoint` |
| RF-03 | Не зацикливаться на `hasMore` без нового курсора | Must | `PollerTest.hasMoreWithoutNewCursorFailsInsteadOfLooping` |
| RF-04 | Перестановка страницы не ломает запись (ровно N новых строк); повтор того же `eventId` гасится журналом | Must | `PollerTest.redeliveredAndReorderedPageProducesNoDuplicateRows` |
| RF-05 | Retry при 429/5xx/timeout, продолжение с того же курсора | Must | `PollerTest.rateLimitKeepsCursorAndResumesFromTheSamePlace`, `transientFailureIsRetriedWithinOneCycle` |
| RF-06 | `DELETE` → tombstone-событие с пустым payload | Must | `PollerTest.deleteBecomesTombstoneEventWithEmptyPayload` |
| RF-07 | `PARTIAL`-запись с `null` не затирает поля | Must | `PollerTest.partialRecordWithNullsDoesNotCrashAndKeepsNullsOutOfPayload` |
| RF-08 | Параллельный цикл того же источника не стартует | Must | `PollerTest.concurrentCycleOfTheSameSourceIsRejected` |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Надёжность | Сдвиг курсора не опережает запись в журнал (at-least-once) | `AC/Poller.java:126-139` | 🟢 |
| Доступность | Сбой отдельного цикла не останавливает расписание | `AC/SourceAdapter.java:82-86` | 🟢 |
| Безопасность | В лог попадает только класс исключения, не содержимое | `AC/SourceAdapter.java:83-85` | 🟢 |

## Критерии приёмки

```gherkin
# Позитив
Дано checkpoint "eam-poller" с курсором C0 и источник, отдающий 2 страницы (hasMore=true, затем false) с курсорами C1, C2
Когда выполняется pollOnce()
Тогда изменения обеих страниц записаны в журнал с eventId "poll:<type>/<id>/v<ver>"
  И checkpoint сохранён после каждой страницы: C1, затем C2
  И результат = ADVANCED, appended = число изменений             # PollerTest.incrementalPollingAdvancesCursorAfterEachPage

# Нет изменений
Дано пустая страница, nextCursor равен текущему, hasMore=false
Когда pollOnce()
Тогда результат UP_TO_DATE, checkpoint не меняется                # 🟡 прямого теста нет (TT-02)

# Негатив: сбой журнала
Дано журнал бросает IllegalStateException на append
Когда pollOnce()
Тогда исключение пробрасывается, курсор прежний                    # PollerTest.journalFailureDoesNotMoveCheckpoint

# Негатив: источник зациклил курсор
Дано страница hasMore=true и nextCursor == cursor
Когда pollOnce()
Тогда IllegalStateException, чтение не продолжается              # PollerTest.hasMoreWithoutNewCursorFailsInsteadOfLooping

# Негатив: 429
Дано источник отвечает 429 с Retry-After
Когда pollOnce()
Тогда после паузы чтение повторяется с того же курсора           # PollerTest.rateLimitKeepsCursorAndResumesFromTheSamePlace

# Негатив: страница в другом порядке
Дано источник отдал 2 новых объекта, порядок страницы переставлен (reorderNextPage)
Когда pollOnce()
Тогда в журнале ровно +2 строки                                    # PollerTest.redeliveredAndReorderedPageProducesNoDuplicateRows
  # Повторная запись того же eventId даёт DUPLICATE на стороне журнала: WebhookHandlerTest.repeatedWebhookIsDuplicateAndWritesNoSecondRow
```

## Приоритет

Все RF — Must (основной канал доставки данных, webhook лишь ускоряет). Поведение `UP_TO_DATE` при `appended > 0` — Could (🟡).

## Трассировка

| Файл | Символ | Тест | Покрытие |
|---|---|---|---|
| `AC/Poller.java:89-96, 120-145` | `pollOnce`, `incremental` | `PollerTest` (см. выше) | 🟢 |
| `AC/EventMapper.java:98-100` | `pollEventId` | `PollerTest.deleteBecomesTombstoneEventWithEmptyPayload` (`poll:IT_SYSTEM/EAM-2001/v2`) | 🟢 |
| `AC/SourceAdapter.java:72-90` | `startPolling` | — | 🟡 |
