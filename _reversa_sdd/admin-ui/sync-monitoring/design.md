# sync-monitoring: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 6..9), §6. Серверная сторона: [`../../flowcharts/admin-console.md`](../../flowcharts/admin-console.md) (`GET /api/sync/sources`, страница из PostgreSQL).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `SyncPage` | `() -> JSX` | вкладки `sources`, `events`, `dlq`, `identity`, `audit`; состояние `tab` | `access/admin-ui/src/pages/SyncPage.tsx:8-39` 🟢 |
| `health` | `(SourceStatus) -> 'ok' \| 'warn' \| 'bad'` | светофор источника | `access/admin-ui/src/pages/sync/SourcesTab.tsx:11-18` 🟢 |
| `SourcesTab` | `() -> JSX` | `useAsync(api.sources, [], SOURCES_REFRESH_MS)` | `access/admin-ui/src/pages/sync/SourcesTab.tsx:28-87` 🟢 |
| `EventsTab` | `() -> JSX` | фильтры `source`, `status`, `from`, `to`, `page`, `open` | `access/admin-ui/src/pages/sync/EventsTab.tsx:48-129` 🟢 |
| `EventDetailCard` | `({source, eventId, onClose}) -> JSX` | `useAsync(api.event(source, eventId))` | `access/admin-ui/src/pages/sync/EventsTab.tsx:10-45` 🟢 |
| `DlqTab` | `() -> JSX` | фильтры `source`, `all`, `page`; `replayed: all ? undefined : false` | `access/admin-ui/src/pages/sync/DlqTab.tsx:11-81` 🟢 |

## Fluxo Principal

1. `SyncPage` показывает `role="tablist"` из пяти кнопок; активная вкладка помечена `aria-selected`; рендерится ровно один `View` (`access/admin-ui/src/pages/SyncPage.tsx:17-37`).
2. **Источники.** `useAsync(() => api.sources(), [], 30_000)`: первый вызов — с индикатором, далее по таймеру `silent` — без. Для каждого элемента `health(s)`: `problems` = есть событий `QUARANTINED`/`RETRYING` > 0; `failedRun` = `lastSyncRun.status === 'FAILED'` или `failed > 0`; `lag = lagSeconds ?? 0`; `bad` при `problems || failedRun || lag > 3600`; `warn` при `lastProjectedAt === null || lastSyncRun === null || lag > 900`; иначе `ok` (`access/admin-ui/src/pages/sync/SourcesTab.tsx:8-18`).
3. **Журнал.** `EventsTab` держит `source/status/from/to/page`; `fromIso = toIso(from)`, `toIsoValue = toIso(to)` участвуют в зависимостях `useAsync`; `filter(set)` оборачивает сеттер: значение + `setPage(0)`; запрос — `api.events({source, status, from, to, page, size: 50})`, пустые значения отбрасывает `query()` (`access/admin-ui/src/pages/sync/EventsTab.tsx:48-69`; `access/admin-ui/src/api/endpoints.ts:20-32`).
4. Клик по `eventId` → `open = {source, eventId}` → `EventDetailCard`; «Закрыть» сбрасывает `open`. Карточка выводит `Object.entries(event)` таблицей (время — для `receivedAt`/`updatedAt`, остальное через `text()`), затем «Причина ошибки», `historyNote` и записи DLQ события (`access/admin-ui/src/pages/sync/EventsTab.tsx:10-45, 126`).
5. **DLQ.** `api.dlq({source, replayed: all ? undefined : false, page, size: 50})`; `source` пустой → не отправляется; смена источника и флажка сбрасывает `page` (`access/admin-ui/src/pages/sync/DlqTab.tsx:13-39`).
6. Таблицы строятся `Loadable` + `Pager`; пустая страница → текст «Событий нет»/«Записей DLQ нет»; данные — только из ответа API (`access/admin-ui/src/pages/sync/EventsTab.tsx:85, 122`; `access/admin-ui/src/pages/sync/DlqTab.tsx:44, 75`).

## Fluxos Alternativos

- Ошибка сети или не-2xx → `role="alert"` «Ошибка запроса (HTTP N)» либо «Не удалось выполнить запрос» 🟢 (`access/admin-ui/src/hooks/useAsync.ts:10-13`).
- Автообновление «Источников» упало → состояние `error` заменяет таблицу (тихий режим влияет только на индикатор загрузки, не на ошибку) 🟡 (`access/admin-ui/src/hooks/useAsync.ts:32-39`).
- Невалидная дата в `datetime-local` → `toIso` возвращает `undefined`, фильтр не отправляется 🟢 (`access/admin-ui/src/components/common.tsx:102-106`).
- Закрытие вкладки/смена раздела прерывает таймер автообновления (`clearInterval` в cleanup) 🟢 (`access/admin-ui/src/hooks/useAsync.ts:45-52`).
- Источник приходит коротким кодом (`eam`), поэтому карточка события обращается к `/api/sync/events/eam/{eventId}` 🟡 (контракт значений проверен только чтением `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:300-301`; фикстуры UI-тестов используют URN — Q-18).
- `eventId` с `/` не открывается (Q-09).

## Dependências

REST: `GET /api/sync/sources`, `/api/sync/events`, `/api/sync/events/{source}/{eventId}`, `/api/sync/dlq` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:56-118`). Данные: `inbox_event`, `consumer_checkpoint`, `dlq_entry` (PostgreSQL, роль `archrag_console_ro`) и `SyncRun` (граф, шаблон `console_latest_sync_runs`). Константы: `SOURCES`, `EVENT_STATUSES`, `SOURCES_REFRESH_MS`, `LAG_*` ([`../contracts.md`](../contracts.md) §7). Спецификации серверных источников данных: [`../../ingestion-service/design.md`](../../ingestion-service/design.md), [`../../event-journal/design.md`](../../event-journal/design.md), [`../../graph-projector/`](../../graph-projector/).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Светофор вычисляется на клиенте из сырых счётчиков, а не отдаётся сервером | `access/admin-ui/src/pages/sync/SourcesTab.tsx:11-18` | 🟢 |
| Пороги lag (15 мин / 60 мин) — константы UI | `access/admin-ui/src/api/constants.ts:61-63` | 🟢 (риск дрейфа — Q-05) |
| «Открытые» записи DLQ = `replayed=false` | `access/admin-ui/src/pages/sync/DlqTab.tsx:17`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:153-159` | 🟢 (работает только если `replayed_at` пишется — Q-02) |
| Автообновление только у «Источников»; журнал и DLQ обновляются действием пользователя | `access/admin-ui/src/pages/sync/SourcesTab.tsx:30` | 🟢 |

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 1)] Ветки `FAILED`/`failed > 0` в светофоре недостижимы (`SyncRun` не завершается) — Q-01.
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] «Только открытые» в DLQ не отличается от «все» (`replayed_at` не пишется) — Q-02.
- 🟡 `eventId` с `/` не открывается — Q-09; фикстуры UI-тестов расходятся с реальным форматом `source` — Q-18.
- 🟡 Время и фильтры периода в локальной зоне — Q-17.
