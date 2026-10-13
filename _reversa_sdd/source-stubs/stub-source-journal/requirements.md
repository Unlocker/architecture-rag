# stub-source-journal: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Wire-формат курсоров и seed: [`../contracts.md`](../contracts.md) §1.2, §4. Контракт `SourceConnector`: [`../../source-spi/requirements.md`](../../source-spi/requirements.md).

## Visão Geral

`StubSource` — управляемая in-memory заглушка одной мастер-системы, реализующая `SourceConnector`. Тест меняет её состояние прямыми вызовами (`upsert`, `upsertPartial`, `delete`), а адаптер читает через `fetchChanges` / `fetchById`. `StubSources` собирает четыре согласованных заглушки (EAM, SCM, CMDB, deploy map) и набор негативных сценариев. Из этого use case вытекают критерии PoC 1–3: срез загружен, изменение доходит до графа, повтор и перестановка не портят граф (`docs/00-vision.md:798-800`).

## Regras de Negócio

- Каждая запись (`upsert`, `upsertPartial`, `delete`) добавляет в журнал `Entry(seq, change, notified)` с `seq = размер журнала + 1`, `updatedAt = clock.instant()`, `sourceVersion = versions.merge("type/id", 1, sum)`; версия монотонна на ключ и растёт также на `DELETE` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:182-190`).
- `upsert` → `UPSERT/COMPLETE`; `upsertPartial` → `UPSERT/PARTIAL` (в payload только переданные поля, значения могут быть `null`); `delete` → `DELETE/COMPLETE` с пустым payload 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:53-70`).
- `fetchChanges(cursor, limit)`: `limit ≤ 0` → `IllegalArgumentException("limit must be positive")` до проверки сбоев; затем `failIfRequested`; затем разбор курсора 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:108-113`).
- Курсор `i<seq>` — инкремент: записи с `seq > after`, не более `limit`, включая `DELETE`; `hasMore = размер журнала > последний seq`; `snapshotComplete = false` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:113-131`).
- Курсор `null` — начало snapshot с зафиксированной границей `bound`; `s<after>:<bound>` — продолжение; иной префикс → `IllegalArgumentException("unknown cursor")` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:132-143`).
- Snapshot отдаёт по каждому ключу последнюю запись с `seq ≤ bound`, только если она `UPSERT`; страница — первые `limit` по `seq` после `after`; последняя страница: `nextCursor = i<bound>`, `snapshotComplete = true`; промежуточные: `nextCursor = s<last>:<bound>`, `hasMore = true` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:144-167`).
- `reorderNextPage()` действует один раз: непустая инкрементальная страница разворачивается, в конец добавляется дубль первой записи; `nextCursor` и `hasMore` остаются вычисленными по исходной странице 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:77-84, 123-130`).
- `failNext(times, status, retryAfter)`: следующие `times` чтений (`fetchChanges` и `fetchById`) бросают `SourceUnavailableException(system, status, retryAfter)`, счётчик убывает на допустимом вызове 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:86-96, 204-209`).
- `suppressWebhooks(true)`: новые записи помечены `notified = false`, не попадают в `webhookEvents()`, но остаются в `fetchChanges`; `webhookEvents()` строит `WebhookEvent(eventId = "<код>-evt-<seq>", …)` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:72-75, 98-105, 188, 192-202`).
- Seed: у `SERVICE.systemCode` (SCM) то же значение, что у `IT_SYSTEM`-id (EAM); у `DEPLOYMENT.service` — id сервиса SCM; у `DEPLOYMENT.hosts` — id `COMPUTE_INSTANCE` CMDB; аннотация `eam-system-id` деплоя — `EamApiSeed.SYSTEM_PAYMENTS_CORE` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:10-15, 83-105`).
- Негативные сценарии deploy map и битая ссылка добавляются только явными хелперами и в seed не входят 🟢 (`platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java:61-69`).
- 🔴 `fetchChanges` с курсором `s5` (без `:`) падает `ArrayIndexOutOfBoundsException` (не `IllegalArgumentException`) — [`../questions.md`](../questions.md) Q-01.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Три типа записи с монотонной версией на ключ | Must | AC-01, AC-02 |
| RF-02 | Snapshot с зафиксированной границей и единственным `snapshotComplete=true` на последней странице | Must | AC-03 |
| RF-03 | Инкрементальный polling по `i<seq>` с пагинацией | Must | AC-04 |
| RF-04 | Подавление webhook при сохранённом polling | Must | AC-05 |
| RF-05 | Перестановка и дубль страницы (out-of-order) | Must | AC-06 |
| RF-06 | Сбои 429/5xx/timeout с обратным отсчётом | Must | AC-07 |
| RF-07 | Seed четырёх источников и согласованность ключей | Must | AC-08 |
| RF-08 | Негативные и сценарные хелперы вне seed | Should | AC-09 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Confiabilidade | Все публичные методы `synchronized` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:29, 54, 62, 68, 73, 82, 92, 103, 108, 171` | 🟢 |
| Desempenho | Каждая страница обходит весь журнал; журнал не ограничен | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:117-122, 147-151` | 🟢 (риск Q-10) |
| Segurança | Время — `Instant` UTC из `Clock`; ни логов, ни секретов | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:34, 187` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: версия монотонна (StubSourceTest.updateBumpsVersionMonotonically)
Дано StubSources.eam(clock), IT_SYSTEM/EAM-1042 имеет sourceVersion v1
Когда upsert("IT_SYSTEM","EAM-1042", name="Payments Core v2")
Тогда возвращённое изменение имеет sourceVersion = v1 + 1, а fetchById отдаёт новый payload

# AC-02: удаление видно по id и инкрементом, но не в snapshot (StubSourceTest.deleteIsVisibleByIdAndIncrementallyButNotInSnapshot)
Дано snapshot прочитан, checkpoint = его nextCursor
Когда delete("IT_SYSTEM","EAM-2001")
Тогда fetchById возвращает DELETE-изменение с новой версией; fetchChanges(checkpoint) отдаёт ровно его; fetchChanges(null) не содержит EAM-2001

# AC-03: snapshot стабилен посреди прохода (StubSourceTest.snapshotPagesAreStableAndOnlyLastIsComplete)
Дано snapshot по 2 объекта, после первой страницы создан EAM-9999
Когда обойдены обе страницы
Тогда собраны ровно TEAM-PAY, EAM-2001, EAM-1042, dep-payments-api-ledger-api; snapshotComplete только у второй страницы; EAM-9999 приходит инкрементом по курсору snapshot

# AC-04: инкремент и граница limit (StubSourceTest.incrementalPollingPagesThroughJournal, invalidLimitIsRejected)
Дано два изменения после курсора snapshot
Когда fetchChanges(cursor, 1) дважды
Тогда страницы по одной записи, hasMore=true затем false, третий вызов пуст
Дано limit = 0
Когда fetchChanges(null, 0)
Тогда IllegalArgumentException

# AC-05: подавленный webhook (StubSourceTest.suppressedWebhookIsMissingFromNotificationsButPresentInPolling)
Дано suppressWebhooks(true), upsert EAM-1042, suppressWebhooks(false), upsert EAM-2001
Когда читаем webhookEvents() и fetchChanges(cursor)
Тогда в уведомлениях +1 событие (EAM-2001), а polling возвращает EAM-1042 и EAM-2001 по порядку

# AC-06: перестановка (StubSourceTest.reorderedPageHasDuplicateAndReversedVersions)
Дано две записи EAM-1042 версий 2 и 3, reorderNextPage()
Когда fetchChanges(cursor, 10)
Тогда версии [3, 2, 2]; повторный вызов с тем же курсором — [2, 3]

# AC-07: сбои (StubSourceTest.rateLimitThenRecovery, serverErrorAffectsFetchByIdAndCountsDown)
Дано failNext(1, 429, 7 с)
Когда fetchChanges(null, 10)
Тогда SourceUnavailableException, isRateLimited, retryAfter = 7 с; следующий вызов успешен
Дано failNext(2, 503, null)
Когда fetchById, fetchChanges, fetchById
Тогда два исключения со статусом 503, третий вызов возвращает объект

# AC-08: seed (StubSourceTest.seededSourcesCoverTheVerticalSlice, seededDeploymentCarriesChartMetaWithThreeAnnotations)
Дано StubSources.seeded(clock)
Тогда присутствуют ключи всех SourceSystem.values(); EAM IT_SYSTEM/EAM-1042, SCM SERVICE/svc-payments-api, CMDB COMPUTE_INSTANCE/vm-pay-01, DEPLOY_MAP DEPLOYMENT/dep-payments-api-prod с format = deploymap-poc-1 и тремя аннотациями; commit — 40 hex в нижнем регистре

# AC-09: хелперы вне seed (StubSourceTest.negativeDeployMapScenariosAreSeparateFromSeed, redeployChangesCommitAndBumpsSourceVersion, brokenReferencePointsToUnknownSystem)
Дано свежий deployMap без хелперов
Тогда dep-unknown-repo-prod отсутствует; после addDeploymentWithUnknownRepository/WithSystemMismatch/WithInvalidCommit все три найдены
Когда addDeploymentRedeploy
Тогда sourceVersion записи dep-payments-api-prod = прежняя + 1, а payload содержит новый commit и не содержит старого

# AC-10 (отказ, непокрыт тестом) 🔴
Дано курсор "s5"
Когда fetchChanges("s5", 10)
Тогда ожидается IllegalArgumentException (и 400 в HTTP-слое), фактически ArrayIndexOutOfBoundsException (Q-01)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-07 | Must | Единственные данные для приёмки PoC |
| RF-08 | Should | Негативные сценарии выбираются отдельными тестами |
| Валидация формата курсора | Won't | Курсор считается непрозрачным и приходит только от самой заглушки (🔴 Q-01) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java` | `StubSource` | 🟢 |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java` | `StubSources` | 🟢 |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java` | `DeployMapFormat` | 🟢 |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java` | 17 тестов | 🟢 |
