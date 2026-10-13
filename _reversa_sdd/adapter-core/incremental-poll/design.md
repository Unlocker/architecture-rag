# incremental-poll, Design

> Модульный дизайн — [`../design.md`](../design.md) (интерфейсы, `InboxWriter`, `EventMapper`, checkpoint-ы). Здесь только поведение сценария. `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## Зависит от / Связанные спецификации

Те же, что у модуля ([`../design.md`](../design.md#зависит-от--связанные-спецификации)): `source-spi` (`SourceConnector.fetchChanges`, `ChangePage`), `event-schemas` (`EventJournal.loadCheckpoint/saveCheckpoint/append`), matrix §2 №1, №6. Retry — [`source-fetch-retry`](../source-fetch-retry/design.md). Вызывающий — `SourceAdapter.startPolling` и `demo-launcher`.

## Интерфейс

`Poller.pollOnce() → PollResult(outcome, appended, syncRunId=null)` (`AC/Poller.java:89-96`). Входы: checkpoint `<code>-poller` (cursor).

## Поток

1. `exclusive(...)`: `running.tryLock()`; не получен → `PollResult(ALREADY_RUNNING, 0, null)` (`AC/Poller.java:107-110`).
2. `loadCheckpoint("<code>-poller", "urn:corp:<code>")`; пусто → [`full-snapshot`](../full-snapshot/design.md); иначе `incremental(source, cp.cursor())` (`:93-94`).
3. Цикл `incremental` (`:120-145`):
   1. `page = fetch(cursor, appended, null)` (с retry);
   2. для каждого `SourceChange c`: `inbox.record(pollEventId(c), c, null, null)`; `appended++` (`:126-129`);
   3. если `page.hasMore() && (nextCursor == null || nextCursor == cursor)` → `IllegalStateException` (`:130-133`);
   4. если `nextCursor != null && != cursor` → `saveCheckpoint("<code>-poller", source, nextCursor)`; `cursor = nextCursor`; `advanced = true` (`:134-139`);
   5. если `!hasMore` → вернуть `ADVANCED` (при `advanced`) или `UP_TO_DATE` с `appended` (`:140-143`); иначе к шагу 1.
4. Любой выход освобождает замок (`finally`, `:115-117`).

## Альтернативные потоки

| Условие | Поведение | Где |
|---|---|---|
| `SourceUnavailableException` исчерпал попытки | `GaveUp` → `GAVE_UP(appended, null)`; уже сохранённые курсоры страниц остаются | `:112-114, 203` |
| Ошибка `append`/`rawStore.put`/`saveCheckpoint` | исключение наружу; курсор не сдвигается для незавершённой страницы | `AC/InboxWriter.java:34-39` |
| `IllegalStateException` коннектора (malformed JSON/change, неожиданный статус) | исключение наружу, цикл прерван (🔴 R-03) | `AC/HttpSourceConnector.java:104-108, 121-127, 129-147` |
| `InterruptedException` в паузе | флаг прерывания восстановлен, `IllegalStateException("poller interrupted")` | `:206-213` |

## Состояние

Только checkpoint `<code>-poller`. Курсор — непрозрачная строка коннектора; адаптер её не разбирает 🟢. Замок — поле `running` (`:50`).

## Решения

| Решение | Evidence | Уверенность |
|---|---|---|
| Курсор после записи всей страницы (at-least-once, дубликаты гасит журнал) | `AC/Poller.java:126-139` | 🟢 |
| Источник считается недоверенным: защита от `hasMore` без прогресса | `:130-133` | 🟢 |
| Курсор двигается по журналу, а не по проекции (расхождение с ADR 0020) | `:22-25` | 🔴 R-02 |

## Риски

- 🟡 U-01: `UP_TO_DATE` при `appended > 0` и неподвижном курсоре (`:134-142`).
- 🔴 R-03: отравленный объект блокирует курсор бессрочно.
- 🟡 Дубль-стоимость: после падения посреди страницы страница читается заново, и все её строки идут через `rawStore.put` + `append` (дешёвый `DUPLICATE`) (`AC/InboxWriter.java:34-39`).
