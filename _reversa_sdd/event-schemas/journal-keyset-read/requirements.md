# Use case: journal-keyset-read, Требования

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

## Обзор
Постраничное чтение журнала для replay, rebuild и диспетчера: `JournalReader.read(JournalQuery)` и `pending(...)` (`ES/JournalReader.java:12-29`) 🟢.

## Правила
- Порядок результата: `(receivedAt, source, eventId)`; `eventId` уникален только в пределах `source` 🟢 (`ES/JournalKey.java:6-10`, `ES/JournalQuery.java:6`).
- `limit` в `1..1000` (`MAX_LIMIT`), иначе `IllegalArgumentException("limit must be in 1..1000")` 🟢 (`ES/JournalQuery.java:25,28-30`).
- Диапазон по `receivedAt` полуоткрыт `[receivedFrom, receivedTo)`; `null` границы не ограничивают; `receivedFrom >= receivedTo` (в т. ч. равны) → IAE `"receivedFrom must be before receivedTo"` 🟢 (`:31-33`).
- `source == null` — все источники; `excludeReplays=true` пропускает строки с `eventId`, начинающимся с `replay:` 🟢 (`:6-14,22`).
- `after` — строго после позиции (`null` — с начала); продолжение страницы — `query.after(JournalKey.of(последняя запись))` 🟢 (`:36-39`; `ES/JournalKey.java:20-22`).
- Keyset устойчив к вставкам во время обхода; чтение не меняет состояние строк 🟢 (`ES/JournalReader.java:7,9-10`).
- `pending(after, retryNotAfter, limit)`: `RECEIVED` всегда; `RETRYING`, `VALIDATED`, `NORMALIZED`, `RESOLVED` — если `updatedAt <= retryNotAfter` (включительно); replay-строки не исключаются; `limit` `1..1000` 🟢 (`ES/JournalReader.java:17-27`). Применимость границы `updatedAt` к `RECEIVED` — по формулировке «ожидающие … а также … не менявшиеся» не уточнена 🟡. Проверка `limit` в `pending` в контракте не реализована (метод интерфейса) 🟡.
- `QUARANTINED` в `pending` не входит 🟢 (не перечислен).

## Функциональные требования
| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-01 | `JournalQuery` отвергает `limit` вне 1..1000 | Must | AC-01 |
| RF-02 | `JournalQuery` отвергает пустой/инвертированный диапазон | Must | AC-02 |
| RF-03 | Полный обход страницами даёт все строки один раз в порядке ключа | Must | AC-03 |
| RF-04 | `excludeReplays` и фильтр `source` | Should | AC-04 |
| RF-05 | `pending` отдаёт необработанные и «застрявшие» строки | Must | AC-05 |

## Критерии приёмки
Тестов `JournalQuery`/`JournalReader` в модуле нет; чтение проверяют `ingestion/event-journal` и `ingestion-service` (replay/rebuild/диспетчер) 🟡.
```gherkin
# AC-01
Дано limit = 0 (или 1001)
Когда создаётся JournalQuery
Тогда IllegalArgumentException

# AC-02
Дано receivedFrom = receivedTo (или from позже to)
Когда создаётся JournalQuery
Тогда IllegalArgumentException

# AC-03
Дано в журнале 5 строк, limit = 2
Когда читаются страницы с продолжением query.after(JournalKey.of(последняя))
Тогда страницы 2+2+1, без пропусков и повторов, порядок (receivedAt, source, eventId)

# AC-04
Дано строки с eventId "replay:r1:ev-1" и "ev-1"; excludeReplays = true
Когда read(query)
Тогда replay-строка отсутствует

# AC-05
Дано RECEIVED, RETRYING (updatedAt позже retryNotAfter) и RESOLVED (updatedAt <= retryNotAfter)
Когда pending(null, retryNotAfter, 100)
Тогда RETRYING с поздним updatedAt не возвращается, RESOLVED возвращается
```

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalQuery.java` | `JournalQuery` | 🟢; тестов нет |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalKey.java` | `JournalKey` | 🟢; тестов нет |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/JournalReader.java` | `JournalReader` | 🟢 |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/StoredEvent.java` | `StoredEvent` | 🟢 |
