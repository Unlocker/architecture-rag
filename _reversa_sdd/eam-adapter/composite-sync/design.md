# composite-sync — дизайн

> Ссылается на [`../design.md`](../design.md) и [`../contracts.md`](../contracts.md) §3.2. `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`. Блок-схемы — [`../../flowcharts/eam-adapter.md`](../../flowcharts/eam-adapter.md) §3, §4.

## Интерфейс

`new CompositeEamConnector(SourceConnector api, SourceConnector legacy)`; `fetchChanges(String cursor, int limit)`; `fetchById(String, String)`; `close()` (`EA/CompositeEamConnector.java:39-116`). В продакшене `api` = `EamApiConnector`, `legacy` = `HttpSourceConnector(EAM, config.sourceBaseUri())` (`EA/EamAdapter.java:67-69`) 🟢; в тестах обе подменяемы (`CompositeEamConnectorTest` подставляет заглушки `SourceConnector`).

## Состояние `State` (`:118-155`)

| Поле | Тип | Значение |
|---|---|---|
| `snapshot` | boolean | `true` — прогон начат с `cursor == null` (полный snapshot); `false` — инкремент |
| `legacyPhase` | boolean | `false` = читаем `eam-api` (`A`), `true` = читаем legacy (`L`) |
| `api` | String? | курсор `EamApiConnector` (его `S/I/W`) или `null` |
| `legacy` | String? | курсор legacy-источника или `null` |

Кодировка: `(snapshot?"S":"I") + ";" + (legacyPhase?"L":"A") + ";eamapi:" + part(api) + "|legacy:" + part(legacy)`; `part(null)="-"`, иначе base64url без паддинга от UTF-8 (`:120-122, 148-150`). Разбор: `split(";",3)`, `split("\\|",2)`, префиксы `eamapi:`/`legacy:` (7 символов), значения режимов — только `S|I`, `L|A`, иначе `IllegalArgumentException`; все ошибки перекрываются в `IllegalArgumentException("malformed eam cursor", e)` (`:124-146`) 🟢.

## Алгоритм `fetchChanges` (`:50-70`)

```
s = cursor == null ? State(snapshot=true, legacyPhase=false, null, null) : stateOf(cursor)
if !s.legacyPhase:                                                    # фаза A
   p = api.fetchChanges(s.api, limit)
   if p.nextCursor == null: throw ISE("eam-api answered a page without a cursor")
   return ChangePage(p.changes,
                     State(s.snapshot, legacyPhase = !p.hasMore, api = p.nextCursor, legacy = s.legacy).encode(),
                     hasMore=true, snapshotComplete=false)
p = legacy.fetchChanges(s.legacy, limit)                              # фаза L
changes = p.changes filtered by type ∈ {TEAM, SERVICE_DEPENDENCY}
if p.hasMore:
   return ChangePage(changes, State(s.snapshot, true, s.api, p.nextCursor).encode(), true, false)
if s.snapshot and !p.snapshotComplete: throw ISE("legacy eam ended snapshot without snapshot-complete")
return ChangePage(changes, State(false, false, s.api, p.nextCursor).encode(), hasMore=false, snapshotComplete=s.snapshot)
```

Итоги 🟢:

- Фаза `A` завершается страницей, у которой `legacyPhase=true` в курсоре; сама эта страница ещё несёт изменения `eam-api` и `hasMore=true` — переход к legacy будет **следующим** вызовом.
- Итоговый курсор `I;A;eamapi:<W,…>|legacy:<курсор legacy>`; следующий цикл идёт обычным инкрементом: `api.fetchChanges("W,…")`, потом legacy.
- Инкрементальный цикл всегда состоит минимум из двух вызовов `fetchChanges` (одна страница `A`, одна страница `L`).
- `ChangePage` разрешает `snapshotComplete` только при `hasMore=false` (`contracts/source-spi/.../ChangePage.java:22-24`); оба возвращаемых варианта этому соответствуют.

## `stateOf` — чужой курсор (`:78-85`)

`State.parse` бросает `IllegalArgumentException` → `LOG.WARNING("eam cursor is not of the composite format, restarting incremental read")` и `State(snapshot=false, legacyPhase=false, null, null)`. Следствия 🟢:

1. `api=null` → `EamApiConnector` стартует как snapshot (`cursor == null`), т.е. читает все три типа с `sort=id`; но составной `ChangePage` всё равно `snapshotComplete=false` — маркер не выдаётся, удалений нет.
2. `legacy=null` → legacy читается своим полным snapshot, фильтруется по типам; маркер игнорируется (`s.snapshot==false`).
3. События идемпотентны (`poll:<type>/<id>/v<ver>`), поэтому перечитывание безопасно; tombstone старых ключей ставит первый настоящий snapshot + reconcile (`docs/06-eam-integration.md` §«Переход живого стенда»).

Граница ловушки: перехватывается только ошибка разбора **составного** формата. Валидный составной курсор с повреждённой `eam-api`-частью принимается `State.parse` (она не разбирается там), а `EamApiCursor.parse` затем бросает `IllegalArgumentException`, который наружу не ловится (`:53`) 🟡 (Q-4).

## `fetchById`, `close`

- `API_TYPES = {PLATFORM, SOLUTION, IT_SYSTEM}` → `api.fetchById`; `LEGACY_TYPES` → `legacy.fetchById`; иначе `Optional.empty()` (`:88-96`).
- `close()`: `closeQuietly(api)` в `try`, `closeQuietly(legacy)` в `finally` (обе будут вызваны); каждое закрытие, только если половина — `AutoCloseable`; исключение закрытия → `WARNING "connector close failed: <класс>"`, без сообщения исключения (`:98-116`) 🟢.

## Зависимости

`EamApiConnector` (см. [`../fetch-changes/design.md`](../fetch-changes/design.md)), `adapters/adapter-core` `HttpSourceConnector` (legacy: `GET /changes`, `GET /objects/{type}/{id}`; ретраи 429/5xx — на `Poller`), `Poller.snapshot` (маркер, чекпойнты, `AC/Poller.java:150-174`) 🟢.

## Связанные спецификации

[`../design.md`](../design.md) §«Зависит от / Связанные спецификации»; `adapter-core` (Poller и Reconciler-контракт) и `graph-projector` (`Reconciler`: tombstone по missing set только после маркера, спец-impact §2 № 7).

## Решения

D-1 (один коннектор), D-2 (legacy как долг PoC), D-8 (чужой курсор) — [`../decisions.md`](../decisions.md).

## Риски и пробелы

- 🟡 Q-4: повреждённая `eam-api`-половина не лечится.
- 🟡 Q-7: если legacy вернёт `nextCursor == null` на последней странице, он кодируется `-` и на каждом инкременте legacy перечитывается полностью. В `eam-api`-половине такая страница отвергается (`:54-56`), для legacy проверки нет.
- 🟡 Типы вне `LEGACY_TYPES` из legacy отбрасываются молча, без счётчика и лога (`:61`).
- 🟡 Если legacy недоступен (`SourceUnavailableException`) после чтения `eam-api`: в инкременте Poller повторяет страницу фазы `L` с тем же курсором (чекпойнт пишется после каждой страницы, `AC/Poller.java:136`); в snapshot курсор между страницами не сохраняется (`AC/Poller.java:150-174`), поэтому после `GaveUp` следующий snapshot-прогон начинается с `eam-api` заново.
