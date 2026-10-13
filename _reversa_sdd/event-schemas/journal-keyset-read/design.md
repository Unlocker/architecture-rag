# Use case: journal-keyset-read, Дизайн

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md). Блок-схема — `_reversa_sdd/flowcharts/event-schemas.md` §4.

## Интерфейс
| Символ | Сигнатура | Примечание |
|---|---|---|
| `JournalReader.read` | `List<StoredEvent> read(JournalQuery)` | не более `limit` |
| `JournalReader.pending` | `List<StoredEvent> pending(JournalKey after, Instant retryNotAfter, int limit)` | replay не исключаются |
| `JournalQuery.after` | `JournalQuery after(JournalKey next)` | копирует все прочие поля |
| `JournalKey.of` | `static JournalKey of(JournalEntry)` | `(receivedAt, source, eventId)` |

## Алгоритм обхода
1. Создать `JournalQuery(source, from, to, excludeReplays, null, limit)` — валидация в конструкторе (`ES/JournalQuery.java:27-34`).
2. `page = reader.read(query)`; обработать строки.
3. Если `page.size() == limit` → `query = query.after(JournalKey.of(page.last().entry()))`, повторить; иначе конец 🟡 (приём «страница неполна → конец» выведен из keyset-контракта; сам контракт полноту страницы не определяет).
4. Для диспетчера: `pending(after, now - задержка, limit)`; курсор — тот же `JournalKey`.

## Зависимости и потребители
Реализация — `PostgresEventJournal` (`ingestion/event-journal`); потребители — `ReplayService` (страницы по 200, `docs/03-interfaces.md:398`), `JournalDispatcher`, rebuild 🟡.

## Риски
- 🟡 Контракт не фиксирует сравнение ключа (регистр/коллация `source`, `eventId`): определяется БД реализации.
- 🟡 `pending` не проверяет `limit` на уровне интерфейса; границу `1..1000` обеспечивает реализация.
