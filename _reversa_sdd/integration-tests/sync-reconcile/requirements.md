# sync-reconcile: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md). Диспетчер и admin REST: [`../../ingestion-service/dispatch-journal/requirements.md`](../../ingestion-service/dispatch-journal/requirements.md), [`../../ingestion-service/replay-rebuild/requirements.md`](../../ingestion-service/replay-rebuild/requirements.md). Reconcile: [`../../adapter-core/full-snapshot/requirements.md`](../../adapter-core/full-snapshot/requirements.md), [`../../graph-projector/requirements.md`](../../graph-projector/requirements.md). Заглушки: [`../../source-stubs/stub-source-journal/requirements.md`](../../source-stubs/stub-source-journal/requirements.md).

## Visão Geral

Пять классов доказывают синхронизацию источников с графом: `AdapterInboxIT` (приём webhook и маркер snapshot в реальном inbox), `JournalDispatcherIT` (фоновый диспетчер, карантин, неполный прогон), `ReconciliationIT` (удаление только после полного snapshot), `SyncAcceptanceIT` (приёмка критериев PoC 1–4 на четырёх адаптерах) и `EamKeyMigrationIT` (переход ключа EAM `EAM-*` → целочисленный `id`). Критерии: срез загружен (1), изменение появляется в графе в пределах p95 ≤ 30 с, reconcile исправляет искусственный пропуск (2), повтор и out-of-order не портят граф (3), rebuild даёт эквивалентный канонический граф (4) (`docs/00-vision.md:798-801`) 🟢. Ловушки: 6 (дедупликация, версии, checkpoint после коммита), 9 (удаление только после полного snapshot), 14 (карантин с кодом).

## Regras de Negócio

- **Приём webhook.** Подписанное уведомление → `202`, в `inbox_event` строка `RECEIVED`, raw лежит в S3 и читается по `payload_key`/`payload_hash`; повтор того же события → `202`, строка одна. Неверная подпись и timestamp на час раньше → `401`, строк нет 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java:99-132`).
- **Маркер snapshot.** `pollOnce` завершает snapshot (`SNAPSHOT_COMPLETED`): в журнале строка `snapshot-complete:<run>` типа `architecture.sync.snapshot-complete.v1`; все события прогона записаны до него (`received_at` позже маркера нет), записей прогона `appended + 1`; checkpoint `eam-poller` создан, курсор начинается с `i` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java:134-150`).
- **Диспетчер.** События, добавленные в журнал, доходят до графа без ручного вызова; битый raw → `QUARANTINED` с кодом `INVALID_RAW_PAYLOAD`, причина без содержимого raw, одна открытая запись DLQ, последующие события проецируются; неизвестный тип → `QUARANTINED`/`UNSUPPORTED_EVENT_TYPE`; маркер без raw → `QUARANTINED`/`MARKER_NO_RAW` и одна запись DLQ 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:121-157, 159-169, 218-227`).
- **Маркер удаляет только при полном прогоне.** Маркер, заявляющий 5 объектов при 0 в журнале прогона, ничего не удаляет: он остаётся `RETRYING` с `RECONCILIATION_TRIGGER_FAILED` и `attempts ≥ 2`, объект `victim` активен. Маркер полного прогона tombstone-ит пропавший объект, остальные остаются 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:171-216`).
- **Сбой посреди snapshot.** Источник отвечает сбоем после первой страницы (обёртка `FailingAfter`): исход `GAVE_UP`, маркера нет, удалённый объект остаётся `active`; рестарт `Poller` над тем же журналом продолжает тот же `syncRunId` и завершает snapshot — теперь объект неактивен, соседи активны 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:201-230`).
- **Граничные случаи reconcile.** Объект, ушедший в карантин в прогоне, не считается пропавшим (`active`); повторный `reconcile` того же маркера даёт `tombstoned = 0`; объект, пришедший webhook-ом в окно snapshot, не удаляется этим прогоном; пропущенное удаление находится полным snapshot (сосед завершается `DUPLICATE`), пропущенное создание появляется 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java:160-199, 232-295`).
- **К1.** После `pollOnce` на четырёх адаптерах и ожидания: цепочки `Service → Deployment → Environment`, `Deployment → ComputeInstance`, `Service → Repository`, `ITSystem → Team`; `ITSystem` с целочисленным ключом (`5101`, `5102`) и связями `USES_SOLUTION` → `USES_PLATFORM`; нет `SourceRecord` EAM `IT_SYSTEM` с `sourceId` на `EAM-`; `ASSERTS` минимум от трёх источников; `DECOMPOSED_INTO` по `eamCode`; `DEPENDS_ON` payments-api → ledger-api ровно один; в журнале нет `QUARANTINED` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:222-273`).
- **К2а.** 20 изменений по одному (чередуются TEAM-PAY через EAM и `svc-ledger-api` через SCM) отправляются подписанным webhook (`202`) и ждут появления узла с новым именем (таймаут 60 с); p95 ≤ 30 с (`SLA_P95`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:277-309`).
- **К2б.** Удаление `T-GONE-*` и создание `T-NEW-*` в заглушке при подавленных webhook: за паузу 2 × интервал диспетчера канонический дамп не меняется; `POST /admin/reconcile/eam` → `202`; затем `T-GONE` неактивен (узел `isCurrent = false`), `T-NEW` активен; активные `IT_SYSTEM` eam-api (2 записи) живы 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:311-346`).
- **К2в.** Изменение `ITSystem` в eam-api (webhook у eam-api нет) подхватывается polling-ом (`ADVANCED`) в пределах 60 с, связь с сервисом сохраняется 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:348-362`).
- **К3.** Повтор того же webhook → `202`, одна строка `inbox_event` с этим `event_id`, дамп графа не меняется; два уведомления в обратном порядке (v2, затем v1) → в графе только `V2`, есть строка `DUPLICATE`, нет `QUARANTINED`; страница polling в перепутанном порядке (`reorderNextPage`) → актуальная версия, старая не применена (`IGNORED_OLD_VERSION` **или** `DUPLICATE`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:366-432`).
- **К4.** `POST /admin/rebuild?confirm=true` → `200`; после обработки журнала канонический дамп (`CanonicalGraph.dump`) равен дампу до rebuild, включая tombstone из К2б и версии из К3; `EXCLUDED_KEYS` пуст 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:436-449`).
- **Миграция ключа EAM** (строго по порядку `m1…m5`): старый стенд с `IT_SYSTEM` `EAM-1042`/`EAM-2001`; новый составной адаптер читает eam-api при старом checkpoint (`ADVANCED`), новые узлы `5101`/`5102` построены, связь SCM достроена по `eamCode`; первый полный snapshot tombstone-ит старые ключи, `TEAM` и `SERVICE_DEPENDENCY` остаются; `POST /admin/rebuild?confirm=true` даёт ровно по одному актуальному `Payments Core` и всего два актуальных `ITSystem`, старые узлы в архиве, `USES_SOLUTION → USES_PLATFORM` на месте; `source_conflict` пуст, кандидаты идентичности только вида «старый ключ — преемник», нет `QUARANTINED` и `dlq_entry` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java:173-261`).
- 🟡 `SyncAcceptanceIT.k2b` и `EamKeyMigrationIT` используют `POST /admin/reconcile|rebuild` с HS256-токеном `architecture.admin` без `iss`/`aud`/`azp` — реальная цепочка JWT здесь заменена (Q-01).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Срез из четырёх источников загружается сквозь адаптеры, журнал и проектор без карантина (К1) | Must | AC-01 |
| RF-02 | Webhook принят, подпись и окно проверяются, изменение доходит до графа p95 ≤ 30 с (К2) | Must | AC-02 |
| RF-03 | Пропущенные изменения исправляет только полный snapshot через reconcile | Must | AC-03 |
| RF-04 | Удаление не происходит на неполном прогоне, в карантине, при повторе маркера | Must | AC-04 |
| RF-05 | Повтор и перестановка не меняют граф (К3) | Must | AC-05 |
| RF-06 | Rebuild воспроизводит эквивалентный канонический граф (К4) | Must | AC-06 |
| RF-07 | Диспетчер переводит негодные события в карантин с кодом и продолжает | Must | AC-07 |
| RF-08 | Миграция ключа EAM не оставляет дублей и карантина | Should | AC-08 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Desempenho | p95 «webhook → граф» ≤ 30 с по 20 замерам; ожидание одного замера ≤ 60 с | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:93-95, 301` | 🟢 |
| Confiabilidade | Объекты идентифицируются уникальным `uid()`; граф и журнал не очищаются между методами `SyncAcceptanceIT` и `EamKeyMigrationIT` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java:73-74` | 🟡 |
| Confiabilidade | Диспетчер в `JournalDispatcherIT`: `poll-interval=100ms`, `batch-size=1`, `retry-delay=1s` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:101-103` | 🟢 |
| Segurança | Подпись webhook обязательна; причина отказа 401 в тесте различается только вердиктом приёмника, не телом | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java:127-128` | 🟡 |
| Segurança | Причина карантина не содержит содержимого raw (`doesNotContain("SECRET")`) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:139, 154` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: К1, позитив (SyncAcceptanceIT.k1_sliceIsLoaded, k1_decomposedIntoLinksSystemToService, k1_dependsOnLinksServices, k1_noQuarantine)
Дано четыре адаптера над заглушками, диспетчер включён
Когда pollOnce на каждом даёт SNAPSHOT_COMPLETED и журнал обработан
Тогда существуют цепочки Service-HAS_DEPLOYMENT->Deployment-IN_ENVIRONMENT->Environment и Deployment-RUNS_ON->ComputeInstance; ITSystem 5101 и 5102 активны, ключей EAM-* нет; DEPENDS_ON payments-api->ledger-api ровно один; QUARANTINED = 0

# AC-02: К2, позитив и отказ (SyncAcceptanceIT.k2a_webhookLatencyP95; AdapterInboxIT.webhookIsStoredAsReceivedRowWithRawObjectAndDuplicateAddsNothing, badSignatureAndStaleTimestampAreRejectedAndNothingIsWritten)
Дано 20 одиночных изменений
Когда каждое отправлено подписанным webhook
Тогда ответ 202, p95 до появления узла в графе ≤ 30 с
Дано webhook с неверной подписью либо timestamp час назад
Когда он отправлен
Тогда 401 и в inbox_event нет строки с этим event_id

# AC-03: К2, reconcile (SyncAcceptanceIT.k2b_missedChangesAreFixedByReconcile)
Дано удаление и создание в заглушке при подавленных webhook
Когда проходит пауза 2 интервала диспетчера
Тогда канонический дамп графа не изменился
Когда POST /admin/reconcile/eam
Тогда 202; пропавшая запись неактивна, узел isCurrent=false; новая запись активна; объекты eam-api живы

# AC-04: удаление только после полного snapshot (ReconciliationIT.missedDeleteIsTombstonedAfterSnapshotAndNeighboursStayActive, failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun, quarantinedSnapshotObjectIsNotTreatedAsMissing, reprocessingTheSameMarkerChangesNothing, objectCreatedByWebhookDuringTheSnapshotWindowSurvivesThisRun; JournalDispatcherIT.snapshotMarkerTombstonesObjectMissingFromRun, markerClaimingMoreObjectsThanJournalHoldsDeletesNothing)
Дано snapshot оборван сбоем 503 после первой страницы
Когда проход закончился GAVE_UP
Тогда маркера snapshot-complete нет, удалённый объект остаётся active
Когда новый Poller завершает тот же syncRunId
Тогда SNAPSHOT_COMPLETED, объект неактивен, соседи активны
Дано маркер заявляет 5 объектов, в журнале 0
Тогда ничего не удалено, маркер RETRYING/RECONCILIATION_TRIGGER_FAILED, attempts >= 2
Дано объект в карантине в прогоне
Тогда он считается присутствующим и остаётся active
Дано повторный reconcile того же маркера
Тогда tombstoned = 0
Дано объект создан webhook-ом в окне snapshot
Тогда этот прогон его не удаляет

# AC-05: К3 (SyncAcceptanceIT.k3a_repeatedEventChangesNothing, k3b_reversedWebhooks, k3c_reorderedPolling)
Когда тот же webhook отправлен дважды
Тогда одна строка inbox_event с этим event_id, дамп графа прежний
Когда webhook v2 пришёл раньше v1
Тогда в графе только V2, есть DUPLICATE, QUARANTINED = 0
Когда страница polling переставлена с дублем
Тогда в графе New, Old отсутствует, у объекта есть IGNORED_OLD_VERSION или DUPLICATE, QUARANTINED = 0

# AC-06: К4 (SyncAcceptanceIT.k4_rebuildGivesEquivalentCanonicalGraph)
Дано журнал обработан
Когда POST /admin/rebuild?confirm=true
Тогда 200 и CanonicalGraph.dump(driver) равен дампу до rebuild

# AC-07: диспетчер (JournalDispatcherIT.brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected, unsupportedTypeIsQuarantinedWithCode, markerWithoutRawGoesToDlq)
Дано событие с битым raw раньше остальных
Тогда оно QUARANTINED/INVALID_RAW_PAYLOAD, причина без SECRET, одна открытая запись DLQ, два следующих события спроецированы
Дано тип события architecture.unknown.v1
Тогда QUARANTINED/UNSUPPORTED_EVENT_TYPE
Дано маркер без raw
Тогда QUARANTINED/MARKER_NO_RAW, одна запись DLQ

# AC-08: миграция ключа EAM (EamKeyMigrationIT.m1…m5)
Дано стенд со старыми ключами EAM-1042/EAM-2001
Когда новый адаптер читает eam-api, затем полный snapshot, затем rebuild
Тогда активны ровно 5101 и 5102, ровно один актуальный Payments Core, старые узлы в архиве, source_conflict пуст, dlq_entry пуст, QUARANTINED = 0

# AC-09 (непокрыто) 🔴
Дано transient-сбой Neo4j внутри executeWrite
Тогда ожидается отсутствие побочных эффектов в PostgreSQL и S3; теста нет (Q-03)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-07 | Must | Критерии PoC 1–4, ловушки 6, 9, 14 |
| RF-08 | Should | Разовая миграция UNLOCKER-255 |
| Измерение polling «интервал + 5 с» | Won't | В `docs/00-vision.md:799` требуется, тестом не измеряется (`k2c` без замера; 🔴 Q-35) |
| Проверка checkpoint адаптера «после коммита Neo4j» | Won't | Косвенно (`ReconciliationIT.java:201-230`), 🔴 Q-10 |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdapterInboxIT.java` | `AdapterInboxIT` (3) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java` | `JournalDispatcherIT` (6) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ReconciliationIT.java` | `ReconciliationIT` (6) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncAcceptanceIT.java` | `SyncAcceptanceIT` (11) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamKeyMigrationIT.java` | `EamKeyMigrationIT` (5) | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/CanonicalGraph.java` | дамп графа (только `SyncAcceptanceIT`) | 🟢 |
