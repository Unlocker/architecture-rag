# Use case: read-journal (страницы журнала, ожидающие события, checkpoint, основа reconciliation)

> Модульные требования и сокращения (`PEJ`, `SQL`, `ES`): [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Обзор

Только чтение (кроме `saveCheckpoint`): `read` для replay/rebuild, `pending` для диспетчера, `loadCheckpoint`/`saveCheckpoint` для адаптеров и проектора, `snapshotContents`/`objectsReceivedSince` для missing set в `Reconciler`.

## Бизнес-правила

1. Порядок всех страниц — `ORDER BY received_at, source, event_id`; продолжение — строго после `(received_at, source, event_id)` ключа `after` (`> (?,?,?)`) 🟢 (`PEJ:183-190`, `PEJ:216-217`).
2. `read`: фильтры `source =`, `received_at >= receivedFrom`, `received_at < receivedTo`, `event_id NOT LIKE 'replay:%'` при `excludeReplays`; `LIMIT = q.limit()` 🟢 (`PEJ:167-190`).
3. Границы `limit` (`1..1000`) и `receivedFrom < receivedTo` проверяет `JournalQuery` при создании, не журнал 🟢 (`ES/JournalQuery.java:25`, `:27-33`).
4. `pending` берёт `status = 'RECEIVED'` **или** `status IN ('RETRYING','VALIDATED','NORMALIZED','RESOLVED') AND updated_at <= retryNotAfter`; `QUARANTINED`, `PROJECTED`, `DUPLICATE`, `SUPERSEDED`, `IGNORED_OLD_VERSION` не выбираются; строки replay не исключаются 🟢 (`PEJ:214-215`).
5. `pending` проверяет `limit` в `1..JournalQuery.MAX_LIMIT` (=1000), иначе `IllegalArgumentException("limit must be in 1..1000")` 🟢 (`PEJ:211-213`).
6. `saveCheckpoint` — upsert `(consumer, source)` → `cursor`, `updated_at`; `loadCheckpoint` без записи → `Optional.empty()` 🟢 (`PEJ:239-272`).
7. `snapshotContents(source, syncRunId)`: все строки с `source = ?` и `starts_with(event_id, 'snap:' || syncRunId || ':')`; возвращает множество `ObjectRef(source_type, source_id)`, число событий и минимальный `received_at` (`null`, если событий нет); статус не учитывается 🟢 (`PEJ:274-299`).
8. `objectsReceivedSince(source, since)`: `DISTINCT (source_type, source_id)` по `source = ?` и `received_at >= since`; любые статусы 🟢 (`PEJ:301-318`).
9. `snapshotContents` грузит результат целиком в память 🟡 (`PEJ:279-298`).

## Требования

| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-R1 | `read` постранично с keyset и фильтрами | Must | AC-R1, AC-R2 |
| RF-R2 | `pending` выбирает `RECEIVED` и созревшие повторы/промежуточные | Must | AC-R3, AC-R4 |
| RF-R3 | Checkpoint upsert и чтение | Must | AC-R5 |
| RF-R4 | `snapshotContents`, `objectsReceivedSince` | Must | AC-R6 |
| RF-R5 | Значения запроса только параметрами; `syncRunId` без `LIKE` | Must | AC-R7 |

## Нефункциональные

| Тип | Требование | Evidence | Метка |
|---|---|---|---|
| Безопасность | SQL собирается из фиксированных фрагментов, значения только `?` | `PEJ:164-190`, `PEJ:214-217` | 🟢 |
| Безопасность | `starts_with` вместо `LIKE` для внешнего `syncRunId` | `PEJ:276-278` | 🟢 |
| Производительность | Страницы ограничены `LIMIT`; максимум 1000 | `PEJ:190`, `PEJ:211`, `ES/JournalQuery.java:25` | 🟢 |
| Производительность | Индексы `(status, received_at)` и `(source, source_type, source_id)`; индекса под `updated_at` и `received_at` без `status` нет | `SQL:24-25` | 🟡 |

## Критерии приёмки

```gherkin
# AC-R1 (позитив: keyset, фильтр replay)
Дано источник S с событиями e0..e4 и replay:r1:e0 (все RECEIVED)
Когда читаем страницами по 2 с excludeReplays=true, продолжая после JournalKey последней строки
Тогда получаем e0,e1,e2,e3,e4 без повторов и пропусков, без replay:r1:e0
И с excludeReplays=false получаем 6 строк, включая replay:r1:e0
# trace: EventJournalIT.readerPagesByKeysetInReceivedOrderAndFilters

# AC-R2 (интервал и негатив: плохой limit)
Дано третье событие с receivedAt=T
Когда read с receivedFrom=T, receivedTo=T+1000ns
Тогда e2 в результате, e0 нет
И read с receivedTo=received_at первого события даёт пусто
И new JournalQuery(..., limit=0) и limit=1001 бросают IllegalArgumentException
# trace: EventJournalIT.readerPagesByKeysetInReceivedOrderAndFilters, readerRejectsBadLimit

# AC-R3 (pending: что выбирается)
Дано a=PROJECTED, b=RETRYING(updated_at=now), c=RETRYING(updated_at=now-120s), d=VALIDATED(now-120s), e=RECEIVED
Когда pending(null, now-60s, 1000)
Тогда результат (по источнику) c, d, e в порядке журнала; a и b не выбраны
# trace: EventJournalIT.pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset

# AC-R4 (pending: keyset и негатив)
Дано то же состояние
Когда обходим pending страницами по 1 с after=JournalKey.of(последней)
Тогда каждая строка встречается ровно один раз
И pending(null, t, 0) бросает IllegalArgumentException
# trace: EventJournalIT.pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset

# AC-R5 (checkpoint)
Дано saveCheckpoint("projector", S, "cursor-1") затем saveCheckpoint(..., "cursor-2") через новые экземпляры журнала
Когда loadCheckpoint("projector", S) на третьем экземпляре
Тогда cursor == "cursor-2"
И loadCheckpoint("other", S) пусто
# trace: EventJournalIT.checkpointSurvivesJournalRestart

# AC-R6 (reconciliation)
Дано в источнике S события snap:run-1:T/a/v1, snap:run-1:T/b/v1 и snap:run-2:T/c/v1
Когда snapshotContents(S, "run-1")
Тогда objects = {(T,a),(T,b)}, events = 2, firstReceivedAt = min(received_at)
И snapshotContents(S, "run-3") даёт пустое множество, events=0, firstReceivedAt=null
# trace: прямого теста на реальном PG нет 🟡 (PollerTest.java:256-258 проверяет на InMemoryJournal; ReconciliationIT использует реальный PostgresEventJournal (pgJournal, ReconciliationIT.java:78, :97) и вызывает оба метода через обёртку, `:391-397`; явных утверждений о содержимом результата в рамках этой спецификации не проверялось)

# AC-R7 (спецсимволы)
Дано syncRunId = "a%b_c"
Когда snapshotContents(S, "a%b_c")
Тогда выбираются только события с префиксом дословно "snap:a%b_c:" (без подстановочных знаков)
# trace: PEJ:276-278; теста нет 🟡
```

## MoSCoW

Must: RF-R1..R5. Won't: фильтры по статусу в `read`, индекс под `pending`.

## Зависит от / связанные спецификации

`ingestion-service` (`JournalDispatcher.java:107` — `pending`; `ReplayService.java:68` — `read`), `adapter-core` (`Poller.java:93`, `:136`, `:170-186` — checkpoint; `EventMapper.java:104` — формат `snap:<syncRunId>:<type>/<id>/v<ver>`), `graph-projector` (`EventProcessor.java:84`, `:615` — checkpoint `projector`; `Reconciler.java:105`, `:113` — missing set), `contracts/event-schemas` (`JournalQuery`, `JournalKey`, `SnapshotContents`, `ObjectRef`, `Checkpoint`), [`../design.md`](../design.md).

## Прослеживаемость

`PEJ:162-318`; `SQL:24-33`; `EventJournalIT` (4 метода).
