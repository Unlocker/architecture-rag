# composite-sync — склейка `eam-api` и legacy в один источник `EAM`

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`. Модуль: [`../requirements.md`](../requirements.md); дизайн — [`design.md`](design.md).

## Обзор

`CompositeEamConnector` — единственный `SourceConnector` источника `EAM` в режиме `eam-api`: сначала читает половину `eam-api` (`PLATFORM`, `SOLUTION`, `IT_SYSTEM`), затем legacy-заглушку (`TEAM`, `SERVICE_DEPENDENCY`). Это один прогон и один маркер `snapshot-complete`, чтобы `Reconciler` не снёс записи другой половины (ADR 0020, UNLOCKER-255). Курсор — строка, непрозрачная для poller'а 🟢 (`EA/CompositeEamConnector.java:13-26`).

## Правила

- Прогон состоит из фазы `A` (eam-api) и фазы `L` (legacy) строго по очереди; фаза `A` всегда отдаёт `hasMore=true`, `snapshotComplete=false` 🟢 (`:52-59`).
- Фаза `L` отбрасывает изменения типов вне `{TEAM, SERVICE_DEPENDENCY}` (в частности, `IT_SYSTEM` legacy) 🟢 (`:30, 61`).
- `snapshotComplete=true` — только если прогон начат как snapshot **и** legacy подтвердил `snapshotComplete` на своей последней странице; иначе `IllegalStateException("legacy eam ended snapshot without snapshot-complete")` 🟢 (`:65-69`).
- Страница `eam-api` без `nextCursor` → `IllegalStateException("eam-api answered a page without a cursor")` 🟢 (`:54-56`).
- Итоговая страница: `hasMore=false`, курсор `I;A;…` (режим инкремента, фаза `A`) 🟢 (`:69`).
- Чужой/битый составной курсор → инкрементальное перечитывание обеих половин с нуля (без маркера), WARN в лог 🟢 (`:78-85`).
- `fetchById`: маршрутизация по `sourceType` (BR-10) 🟢 (`:88-96`).
- `close()` закрывает обе половины; ошибки закрытия логируются классом исключения, не значениями 🟢 (`:98-116`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|---|---|---|---|
| FS-01 | Snapshot покрывает обе половины; `IT_SYSTEM` legacy отброшен, `IT_SYSTEM` — только из `eam-api` с `sourceId` 5101, 5102 | Must | `snapshotCoversBothHalvesAndDropsLegacyItSystem` (`ET/CompositeEamConnectorTest.java:73`) |
| FS-02 | Маркер только на самой последней странице, после legacy-данных; все прочие страницы `hasMore=true` и без маркера | Must | `snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf` (`:82`) |
| FS-03 | Инкремент от курсора snapshot видит изменения обеих половин, без `IT_SYSTEM` legacy и без маркера | Must | `incrementalFromSnapshotCursorSeesChangesOfBothHalves` (`:119`) |
| FS-04 | Инкремент без изменений даёт пусто, `hasMore=false`, курсор пригоден для следующего цикла | Must | `incrementalWithoutChangesFinishesWithStableCursor` (`:134`) |
| FS-05 | Каждая страница с `hasMore` имеет новый (уникальный) курсор | Must | `everyPageWithMoreAdvancesCursor` (`:144`) |
| FS-06 | `fetchById` маршрутизирует: `IT_SYSTEM`/`PLATFORM` → eam-api, `TEAM` → legacy, `IT_SYSTEM` с ключом `EAM-1042` → пусто, `UNKNOWN` → пусто | Should | `fetchByIdRoutesByTypeAndKeepsLegacyTeamWebhookWorking` (`:151`) |
| FS-07 | Legacy без маркера не даёт маркера составного прогона | Must | `legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker` (`:161`) |
| FS-08 | Чужой курсор (`"12"`) → инкремент без маркера; итоговый курсор уже составной и даёт пустой следующий цикл | Must | `foreignCursorOfTheOldAdapterRestartsIncrementalReadWithoutSnapshotMarker` (`:186`) |
| FS-09 | `eam-api` без курсора — ошибка | Should | `upstreamPageWithoutCursorIsAnError` (`:197`) |

## Критерии приёмки (Дано/Когда/Тогда)

```gherkin
Сценарий: полный snapshot составного источника
  Дано eam-api с seed (2+2+2 объекта) и legacy-заглушка с TEAM и SERVICE_DEPENDENCY
  Когда poller читает fetchChanges(null, 2) до hasMore == false
  Тогда типы в изменениях: IT_SYSTEM, PLATFORM, SERVICE_DEPENDENCY, SOLUTION, TEAM
  И все страницы кроме последней — hasMore=true, snapshotComplete=false
  И последняя — hasMore=false, snapshotComplete=true, первая страница с legacy-типами позже последней страницы eam-api-типов
  # CompositeEamConnectorTest.snapshotCoversBothHalvesAndDropsLegacyItSystem, snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf

Сценарий: инкремент обеих половин
  Дано курсор последней страницы snapshot, затем создана платформа 3999 и legacy TEAM-NEW
  Когда читается инкремент до hasMore == false
  Тогда в изменениях есть PLATFORM/3999 и TEAM/TEAM-NEW, нет IT_SYSTEM, snapshotComplete=false
  # CompositeEamConnectorTest.incrementalFromSnapshotCursorSeesChangesOfBothHalves

Сценарий: переход со старого адаптера
  Дано сохранённый курсор старого формата "12"
  Когда poller вызывает fetchChanges("12", 100) и дочитывает до конца
  Тогда ни одна страница не несёт snapshotComplete
  И в изменениях есть IT_SYSTEM (из eam-api) и TEAM; итоговый курсор нового формата, следующий цикл пуст
  # CompositeEamConnectorTest.foreignCursorOfTheOldAdapterRestartsIncrementalReadWithoutSnapshotMarker

Сценарий (негатив): legacy не подтверждает полноту
  Дано legacy-половина, возвращающая hasMore=false и snapshotComplete=false во время snapshot
  Когда составной коннектор доходит до последней страницы
  Тогда IllegalStateException, маркер не выдаётся
  # CompositeEamConnectorTest.legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker

Сценарий (негатив): eam-api вернул страницу без курсора
  Дано eam-api-половина, возвращающая nextCursor == null
  Когда fetchChanges(null, 10)
  Тогда IllegalStateException
  # CompositeEamConnectorTest.upstreamPageWithoutCursorIsAnError

Сценарий (негатив): повреждённая eam-api-часть составного курсора
  Дано курсор "S;A;eamapi:<валидный base64 'garbage'>|legacy:-"
  Когда fetchChanges
  Тогда IllegalArgumentException("malformed eam-api cursor") — не лечится автоматически
  # теста нет 🟡 (вопрос Q-4)
```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| FS-01…FS-04, FS-07, FS-08 | Must | корректность reconcile и переход стенда |
| FS-05, FS-06, FS-09 | Should | защита от зацикливания/маршрутизация |
| Удаление legacy-половины | Won't (здесь) | «долг PoC», отдельная задача про реальный EAM |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `EA/CompositeEamConnector.java:50-70` | `fetchChanges` | 🟢 |
| `EA/CompositeEamConnector.java:78-85` | `stateOf` | 🟢 |
| `EA/CompositeEamConnector.java:88-96` | `fetchById` | 🟢 |
| `EA/CompositeEamConnector.java:98-116` | `close`, `closeQuietly` | 🟡 (тесты вызывают `close()` в `tearDown`, отдельной проверки нет) |
| `EA/CompositeEamConnector.java:118-155` | `State` | 🟢 |
