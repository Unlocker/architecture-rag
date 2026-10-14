# sync-monitoring: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 6..9). Вкладки Identity и «Аудит» — в [`../identity-audit/requirements.md`](../identity-audit/requirements.md).

## Visão Geral

Раздел «Синхронизация» (`#/sync`) показывает оператору состояние конвейера: вкладки «Источники» (светофор по четырём источникам), «Журнал событий» (inbox с фильтрами и карточкой события без payload) и «DLQ» (карантин с кодом и причиной). Данные читает консоль из PostgreSQL ролью `archrag_console_ro` и из графа (`SyncRun`); UI ничего не меняет — повтор, выход из карантина и reconcile выполняются через admin REST ingestion вне UI 🟢 (`access/admin-ui/src/pages/SyncPage.tsx:8-39`; `docs/adr/0026-admin-console-readonly-loopback.md:18`).

## Regras de Negócio

- Вкладка по умолчанию — «Источники»; выбор вкладки живёт в состоянии страницы (после перезагрузки вкладка сбрасывается) 🟢 (`access/admin-ui/src/pages/SyncPage.tsx:8-14, 18`).
- «Источники»: таблица из `GET /api/sync/sources`; обновляется автоматически раз в 30 с без сброса экрана и вручную кнопкой «Обновить» (с индикатором загрузки) 🟢 (`access/admin-ui/src/pages/sync/SourcesTab.tsx:28-34`; `access/admin-ui/src/api/constants.ts:58-59`; `access/admin-ui/src/hooks/useAsync.ts:45-54`).
- Светофор `health(s)`: `bad`, если есть события `QUARANTINED` или `RETRYING`, **или** последний `SyncRun` в статусе `FAILED`, **или** `failed > 0`, **или** `lagSeconds > 3600`; иначе `warn`, если нет `lastProjectedAt`, нет `lastSyncRun`, последний прогон `RUNNING` или `lagSeconds > 900`; иначе `ok`. Подписи: `ok` — «норма», `warn` — «идёт прогон» (для `RUNNING`) или «нет данных», `bad` — «проблемы» 🟢 (`access/admin-ui/src/pages/sync/SourcesTab.tsx:8-18, 58`; `access/admin-ui/src/api/constants.ts:62-63`).
- 🟢 [UNLOCKER-280: реализовано] Ветки `FAILED` и `failed > 0` достижимы: `SyncRun` создаётся `RUNNING`, закрывается по маркеру `snapshot-complete` (`SUCCEEDED`/`FAILED`, `endedAt`, счётчики), зависшие `RUNNING` старше `archrag.sync-run.stale-after` переводит в `FAILED` фоновая задача (Q-01).
- Lag форматируется: `null` → «—», < 60 с → «N с», < 3600 → «N мин», иначе «N ч» (целые, округление вниз) 🟢 (`access/admin-ui/src/pages/sync/SourcesTab.tsx:20-25`).
- Колонка «Последний SyncRun»: статус, время старта и окончания (`endedAt`, если есть), `fetched/applied/failed` (`—` для `null`); без прогона — «—». Колонка «События по статусам» — только ненулевые; колонка «Ошибки» — всегда `QUARANTINED: N, RETRYING: N` 🟢 (`access/admin-ui/src/pages/sync/SourcesTab.tsx:53-77`).
- «Журнал событий»: фильтры источник (`SOURCES`), статус (`EVENT_STATUSES`), период «С»/«По» (`datetime-local` в локальной зоне → ISO UTC через `toIso`; пустое или невалидное значение не отправляется); любое изменение фильтра сбрасывает страницу на 0; размер страницы 50 🟢 (`access/admin-ui/src/pages/sync/EventsTab.tsx:8, 57-69, 73-84`; `access/admin-ui/src/components/common.tsx:101-106`).
- Клик по `eventId` открывает карточку `GET /api/sync/events/{source}/{eventId}` (оба сегмента кодируются): все поля события таблицей (`receivedAt`/`updatedAt` как время), «Причина ошибки», `historyNote`, список записей DLQ события; **payload в карточке нет** — только `payloadRef` и `contentHash` 🟢 (`access/admin-ui/src/pages/sync/EventsTab.tsx:10-45, 107-113`; `access/admin-ui/src/api/types.ts:108-127`).
- «DLQ»: по умолчанию `replayed=false` («только открытые»); флажок «Включая повторённые» убирает параметр; фильтр источника; смена фильтра сбрасывает страницу 🟢 (`access/admin-ui/src/pages/sync/DlqTab.tsx:13-19, 27-39`).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] `dlq_entry.replayed_at` не заполняется ни replay, ни диспетчером, поэтому «открытые» = все записи, колонка «Повтор» всегда «—» (Q-02).
- Причина DLQ и код — недоверенный текст, выводится как текст 🟢 (`access/admin-ui/src/pages/sync/DlqTab.tsx:66-67`; `access/admin-ui/src/pages/SyncPage.test.tsx:179`).
- Сервер отвергает неизвестные `source`/`status` (400), `from ≥ to` (400), `size` вне 1..200 (400) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:86-91`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java:17-22`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Таблица источников со светофором, прогоном, lag, счётчиками | Must | AC-01, AC-02 |
| RF-02 | Автообновление раз в 30 с и ручное обновление | Should | AC-03 |
| RF-03 | Журнал событий с фильтрами, пагинацией и сбросом страницы | Must | AC-04, AC-05 |
| RF-04 | Карточка события без payload, с записями DLQ | Must | AC-06 |
| RF-05 | DLQ: открытые по умолчанию, переключатель, пустое состояние | Should | AC-07 |
| RF-06 | Состояния загрузки, ошибки и пусто для каждой вкладки | Must | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Payload источника консоль не получает; UI показывает `payloadRef` и `contentHash` | `access/admin-ui/src/api/types.ts:108-127`; `docs/adr/0026-admin-console-readonly-loopback.md:20` | 🟢 |
| Безопасность | Тексты причин и кодов — как текст | `access/admin-ui/src/pages/sync/DlqTab.tsx:66-67`; `access/admin-ui/src/pages/sync/EventsTab.tsx:31, 36` | 🟢 |
| Производительность | Автообновление только у вкладки «Источники»; остальные — по действию | `access/admin-ui/src/pages/sync/SourcesTab.tsx:30`; `access/admin-ui/src/pages/sync/EventsTab.tsx:59-62` | 🟢 |
| Надёжность | Тихое обновление не сбрасывает таблицу в «Загрузка…» | `access/admin-ui/src/hooks/useAsync.ts:26-31`; `access/admin-ui/src/pages/SyncPage.test.tsx:107-108` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (SyncPage.test.tsx «sources screen shows a traffic light per source…», «health rules»)
Дано четыре источника: eam (норма, lag 125 с), scm (3 события QUARANTINED), cmdb (нет прогона и lastProjectedAt), deploymap (прогон FAILED, failed=2)
Когда открыта вкладка «Источники»
Тогда строки: «норма» и «2 мин»; «проблемы» с «QUARANTINED: 3»; «нет данных»; «проблемы»

# AC-02 (SyncPage.test.tsx «lag raises the traffic light»)
Дано источник без проблем, lagSeconds = 20 минут
Тогда health = warn
Дано lagSeconds = 24 часа
Тогда health = bad

# AC-03 (SyncPage.test.tsx «sources refresh every 30 seconds and manually», «sources show error state»)
Дано вкладка «Источники» открыта (1 запрос)
Когда прошло 29 с — запросов 1; прошло 30 с — запросов 2, текст «Загрузка…» не появляется
Когда нажато «Обновить»
Тогда запросов 3
Дано сервер ответил 503
Тогда «Ошибка запроса (HTTP 503)»

# AC-04 (SyncPage.test.tsx «events journal sends source, status and period filters and resets the page»)
Дано открыт журнал, нажато «Вперёд» (page=1)
Когда выбраны источник eam, статус QUARANTINED, период С/По
Тогда последний запрос: /api/sync/events?source=eam&status=QUARANTINED&from=<ISO UTC>&to=<ISO UTC>&page=0&size=50

# AC-05 (SyncPage.test.tsx «events journal paginates», «events journal shows empty state»)
Дано total = 120, size = 50
Тогда «Страница 1 из 3 (всего 120)», «Назад» заблокирована; «Вперёд» → page=1
Дано items = []
Тогда «Событий нет»

# AC-06 (SyncPage.test.tsx «event card shows metadata and DLQ records but never payload»)
Дано событие e-1 QUARANTINED с payloadRef и contentHash
Когда нажато на eventId
Тогда запрошена карточка по ключу (source, eventId), видны «Причина ошибки: …», payloadRef, contentHash и запись DLQ; причина «<b>bad</b>» показана текстом; поля payload нет

# AC-07 (SyncPage.test.tsx «DLQ shows reasons and asks only for open entries by default», «DLQ shows empty state»)
Дано открыта вкладка DLQ
Тогда запрос /api/sync/dlq?replayed=false&page=0&size=50
Когда включён «Включая повторённые»
Тогда запрос /api/sync/dlq?page=0&size=50
Дано items = []
Тогда «Записей DLQ нет»

# AC-08 (негатив, SyncPage.test.tsx «sources show error state»)
Дано GET любой вкладки завершился ошибкой
Тогда показан role=alert «Ошибка запроса (HTTP N)», тело ответа не выводится
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Источники, журнал, карточка (RF-01, RF-03, RF-04) | Must | Единственный обзор состояния конвейера |
| Автообновление, DLQ (RF-02, RF-05) | Should | Диагностика; DLQ опирается на недоделанный признак (Q-02) |
| Действия над событиями (повтор, выход из карантина) | Won't | Запрещены ADR 0026; REST-операции для карантина нет (Q-02 `../../ingestion-service/questions.md`) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-ui/src/pages/SyncPage.tsx` | `SyncPage` | 🟢 |
| `access/admin-ui/src/pages/sync/SourcesTab.tsx` | `SourcesTab`, `health`, `formatLag` | 🟢 |
| `access/admin-ui/src/pages/sync/EventsTab.tsx` | `EventsTab`, `EventDetailCard` | 🟢 |
| `access/admin-ui/src/pages/sync/DlqTab.tsx` | `DlqTab` | 🟢 |
| `access/admin-ui/src/pages/SyncPage.test.tsx` | 16 тестов (общих с `identity-audit`) | 🟢 |
