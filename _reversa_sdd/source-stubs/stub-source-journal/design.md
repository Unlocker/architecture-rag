# stub-source-journal: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Контракты (курсоры, seed): [`../contracts.md`](../contracts.md) §1.2, §4, §5.

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `StubSource` | `final class implements SourceConnector`; `(SourceSystem, Clock)` | потокобезопасна | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:29, 43-46` 🟢 |
| `upsert` | `(String type, String id, Map<String,Object>) -> SourceChange` | `UPSERT/COMPLETE` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:54-56` 🟢 |
| `upsertPartial` | то же | `UPSERT/PARTIAL` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:62-65` 🟢 |
| `delete` | `(type, id) -> SourceChange` | `DELETE/COMPLETE`, payload `{}` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:68-70` 🟢 |
| `fetchChanges` | `(String cursor, int limit) -> ChangePage` | см. «Fluxo Principal» | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:107-168` 🟢 |
| `fetchById` | `(type, id) -> Optional<SourceChange>` | последняя запись ключа | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:170-180` 🟢 |
| `webhookEvents` | `() -> List<WebhookEvent>` | неподавленные, в порядке `seq` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:103-105` 🟢 |
| `suppressWebhooks` / `reorderNextPage` / `failNext` | см. модульный дизайн | сценарные ручки | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:73-96` 🟢 |
| `StubSources.seeded` и четыре фабрики | `(Clock)` | seed | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:29-105` 🟢 |
| хелперы негативных сценариев | `(StubSource) -> String` | id добавленного объекта | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:121-178, 207-211` 🟢 |
| `DeployMapFormat` | константы формата | | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:24-47` 🟢 |

## Fluxo Principal

Запись (`append`) `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:182-190`:

1. `version = versions.merge("<type>/<id>", 1L, Long::sum)`.
2. `change = new SourceChange(type, id, version, op, completeness, clock.instant(), payload)` (payload копируется в `LinkedHashMap` с сохранением `null`-значений: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceChange.java:40-41`).
3. `journal.add(new Entry(journal.size() + 1, change, !webhooksSuppressed))`.

Чтение `fetchChanges(cursor, limit)` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:108-168`:

1. `limit ≤ 0` → `IllegalArgumentException`.
2. `failIfRequested()` (`failuresLeft > 0` → `failuresLeft--`, `SourceUnavailableException(system, failureStatus, failureRetryAfter)`).
3. Курсор начинается с `i`: `after = parseLong(cursor[1:])`; собрать до `limit` записей журнала с `seq > after`, `last` = `seq` последней; если взведён `reorderNextPage` и страница непуста — сбросить флаг, `reverse(page)`, добавить в конец дубль первой исходной записи; вернуть `ChangePage(page, "i"+last, journal.size() > last, false)`.
4. Курсор `null`: `bound = journal.size()`, `after = 0`. Курсор `s…`: `parts = split(":")`, `after = parts[0]`, `bound = parts[1]`. Иной — `IllegalArgumentException("unknown cursor")`.
5. `byKey` — по ключу `type/id` последняя запись с `seq ≤ bound`; `latest` — `TreeMap<seq, change>` только `UPSERT`-записей `byKey`.
6. Страница — первые `limit` элементов `latest.tailMap(after, false)`; `last` — `seq` последнего; `more` = в `latest` есть `seq > last`.
7. Вернуть `ChangePage(page, more ? "s"+last+":"+bound : "i"+bound, more, !more)`.

Чтение `fetchById(type, id)`: `failIfRequested()`, затем линейный просмотр журнала, запоминается последняя запись с совпавшими `sourceType` и `sourceId`; результат `Optional` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:171-179`) 🟢.

## Fluxos Alternativos

- **Подавленные webhook**: запись помечена `notified = false`; `fetchChanges`/`fetchById` видят её нормально, `webhookEvents()` — нет 🟢.
- **Пустой журнал или нет изменений после курсора**: инкрементальная страница пуста, `nextCursor = "i"+after`, `hasMore = false` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:116, 130`).
- **Snapshot пустого журнала**: `latest` пуст, `more = false` → страница пуста, `nextCursor = "i0"`, `snapshotComplete = true` 🟡 (выведено из кода, теста нет).
- **Объект изменился между страницами snapshot**: попадает на следующем проходе или инкрементом после `i<bound>` (граница `bound` фиксирована в курсоре) 🟢.
- **Повторный upsert после delete**: версия продолжает нумерацию; в snapshot объект вернётся, так как последняя запись ключа — `UPSERT` 🟢.

## Dependências

`contracts/source-spi` (`SourceConnector`, `ChangePage`, `SourceChange`, `ChangeOperation`, `Completeness`, `SourceSystem`, `SourceUnavailableException`, `WebhookEvent`) — [`../../source-spi/design.md`](../../source-spi/design.md). `Clock`. `EamApiSeed` — только как источник константы `SYSTEM_PAYMENTS_CORE`/`SYSTEM_LEDGER` для seed деплоя (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:101, 127, 144`) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| `seq = размер журнала + 1` (журнал только растёт, записи не удаляются) | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:188` | 🟢 |
| Snapshot считается по `bound`, а не по текущему состоянию: даёт стабильные страницы при параллельных записях | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:132-136, 146-151` | 🟢 |
| Версия растёт на `DELETE` и продолжается после него | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:185` | 🟢 |
| `eventId` webhook = `<код>-evt-<seq>`: уникален в источнике, стабилен при повторной доставке | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:195`; `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookEvent.java:9-10` | 🟢 |
| Хелперы негативных сценариев статические и принимают `StubSource` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:121, 138, 153, 169, 207` | 🟢 |

## Observabilidade

Нет логов и метрик; диагностика через исключения и состояние журнала в тестах.

## Riscos e Lacunas

- 🔴 Курсор `s…` без `:` → `ArrayIndexOutOfBoundsException` (Q-01).
- 🟡 Snapshot отдаёт `PARTIAL`-запись как есть; нет теста (Q-07).
- 🟡 O(N) на страницу, журнал не ограничен (Q-10).
- 🟡 `fields(Object...)` падает на нечётном числе аргументов (Q-09).
- 🟡 В seed `StubSources.eam` присутствуют строковые `IT_SYSTEM` `EAM-1042/EAM-2001`, которые после перехода на `eam-api` адаптер отбрасывает (Q-15).
