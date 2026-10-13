# composite-sync — задачи

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`.

## Pre-requisites

- [ ] `EamApiConnector` и `HttpSourceConnector` реализуют `SourceConnector` и (опционально) `AutoCloseable`.
- [ ] Контракт `ChangePage`: `snapshotComplete` только при `hasMore=false`.

## Задачи

- [ ] T-C1 Константы `LEGACY_TYPES={TEAM,SERVICE_DEPENDENCY}`, `API_TYPES={PLATFORM,SOLUTION,IT_SYSTEM}`, логгер, конструктор, `system()=EAM`.
  - Origin: `EA/CompositeEamConnector.java:30-47`
  - Критерий готовности: `system()` возвращает `SourceSystem.EAM`.
  - Уверенность: 🟢
- [ ] T-C2 Запись `State` с `encode`/`parse`/`mode`/`part`/`unpart`.
  - Origin: `EA/CompositeEamConnector.java:118-155`
  - Критерий готовности: `parse(encode(s)) == s` для `snapshot∈{S,I}`, `legacyPhase∈{A,L}`, курсоров `null`/значение; неверный режим/префикс/разделитель/base64 → `IllegalArgumentException("malformed eam cursor")`.
  - Уверенность: 🟢
- [ ] T-C3 `fetchChanges`: фаза A, фаза L, фильтр типов, проверка `snapshotComplete`, итоговая страница.
  - Origin: `EA/CompositeEamConnector.java:50-70`
  - Критерий готовности: `snapshotCoversBothHalvesAndDropsLegacyItSystem`, `snapshotCompleteOnlyOnVeryLastPageAfterLegacyHalf`, `incrementalFromSnapshotCursorSeesChangesOfBothHalves`, `incrementalWithoutChangesFinishesWithStableCursor`, `everyPageWithMoreAdvancesCursor`, `legacyHalfWithoutSnapshotCompleteDoesNotProduceCompleteMarker`, `upstreamPageWithoutCursorIsAnError` зелёные.
  - Уверенность: 🟢
- [ ] T-C4 `stateOf`: чужой курсор → инкрементальный рестарт + WARN.
  - Origin: `EA/CompositeEamConnector.java:72-85`
  - Критерий готовности: `foreignCursorOfTheOldAdapterRestartsIncrementalReadWithoutSnapshotMarker` зелёный.
  - Уверенность: 🟢
- [ ] T-C5 `fetchById` по типу.
  - Origin: `EA/CompositeEamConnector.java:88-96`
  - Критерий готовности: `fetchByIdRoutesByTypeAndKeepsLegacyTeamWebhookWorking` зелёный.
  - Уверенность: 🟢
- [ ] T-C6 `close` / `closeQuietly`.
  - Origin: `EA/CompositeEamConnector.java:98-116`
  - Критерий готовности: обе половины закрываются, даже если первая бросила исключение; в логе — только класс исключения.
  - Уверенность: 🟡 (тестов нет)

## Тестовые задачи

Есть: девять тестов `CompositeEamConnectorTest` (`ET/CompositeEamConnectorTest.java:73-197`).

Пробелы (🟡):

- [ ] TT-C1 Round-trip `State.encode/parse` для всех комбинаций; `-` ↔ `null`.
- [ ] TT-C2 Повреждённая `eam-api`-часть валидного составного курсора → зафиксировать `IllegalArgumentException` (Q-4).
- [ ] TT-C3 `close()` вызывает `close` обеих половин, даже если первая бросает; сообщение логов без значений.
- [ ] TT-C4 Legacy возвращает `nextCursor == null` на последней странице (Q-7).
- [ ] TT-C5 Изменения legacy неразрешённых типов (`IT_SYSTEM`, `PLATFORM`) отбрасываются и в инкременте.

## Порядок

T-C1 → T-C2 → T-C3 (+T-C4) → T-C5 → T-C6.

## Открытые 🔴

Нет 🔴 в сценарии; 🟡 — Q-4, Q-7 в [`../questions.md`](../questions.md).
