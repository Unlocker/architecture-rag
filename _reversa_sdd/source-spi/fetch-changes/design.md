# fetch-changes — дизайн

> Модуль: [`../design.md`](../design.md). Блок-схема: `_reversa_sdd/flowcharts/source-spi.md` §3.

## Interface
`ChangePage fetchChanges(String cursor, int limit) throws SourceUnavailableException` (`SPI/SourceConnector.java:24`); `ChangePage(List<SourceChange> changes, String nextCursor, boolean hasMore, boolean snapshotComplete)` (`SPI/ChangePage.java:17-18`). Типы полей — [`../contracts.md`](../contracts.md) §3-4.

## Fluxo Principal
Протокол потребителя (`Poller`, `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java`):
1. Snapshot: `cursor = null` → `fetchChanges(cursor, pageSize)` (`Poller.java:195`).
2. Для каждой страницы: записать все `changes` в inbox, затем сдвинуть курсор (`:121-137`, `:150-158`).
3. `hasMore == true` → `cursor = nextCursor` и повторить; `nextCursor == null` или равный текущему → `IllegalStateException` (`:130-132`, `:157-159`).
4. Последняя страница snapshot: нужен `snapshotComplete == true` и `nextCursor != null`, иначе `IllegalStateException` (`:164-167`); затем маркер `snapshot-complete`, потом checkpoint'ы (`:169-171`).
5. Инкрементально: курсор сохраняется после append всей страницы (`:134-139`), на последней странице `nextCursor` — следующая точка опроса.

Конструктор `ChangePage` (SPI): `changes = List.copyOf(changes)`; `snapshotComplete && hasMore` → `IllegalArgumentException("snapshotComplete is only valid on the last page")` (`SPI/ChangePage.java:20-25`).

## Fluxos Alternativos
- **429/5xx/timeout:** `SourceUnavailableException`; `Poller.fetch` повторяет до `maxAttempts`, пауза — `RetryPolicy.delay` (`Poller.java:187-200`) 🟢; после исчерпания — `PollResult.Outcome.GAVE_UP` (`:112-113`) 🟢.
- **Пустая страница:** изменений нет; удалений не делать 🟢.
- **Неполный snapshot:** missing set не обрабатывается (маркер не пишется) 🟢.

## Dependências
Реализации порта: `HttpSourceConnector` (`GET /changes?limit=&cursor=`, `AC/HttpSourceConnector.java:51-68`), `EamApiConnector`, `StubSource`. Потребитель — `Poller`.

## Decisões de Design Identificadas
| Решение | Evidence | Уверенность |
|---|---|---|
| Курсор непрозрачен, на последней странице служит курсором polling | `SPI/ChangePage.java:12-13` | 🟢 |
| Страница неизменяема | `SPI/ChangePage.java:21` | 🟢 |
| Исключение не хранит тело ответа | `SPI/SourceUnavailableException.java:19` | 🟡 |

## Estado Interno
Нет в SPI; курсор/checkpoint хранит `Poller` в журнале.

## Observabilidade
Нет.

## Riscos e Lacunas
- 🟡 Положительность `limit` не принуждается интерфейсом.
- 🔴 Нет тестов `ChangePage` и `SourceUnavailableException` в модуле ([`../questions.md`](../questions.md) Q-03).
- 🟡 `HttpSourceConnector` читает `hasMore`/`snapshotComplete` как `Boolean.TRUE.equals`: отсутствующее поле = `false` (`AC/HttpSourceConnector.java:66-67`), т. е. источник, не вернувший `snapshotComplete`, никогда не запустит reconcile.
