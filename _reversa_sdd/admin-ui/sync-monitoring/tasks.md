# sync-monitoring: задачи

## Pré-requisitos
- [ ] Общий каркас ([`../tasks.md`](../tasks.md) T-03..T-08); REST `/api/sync/**` консоли

## Tarefas

- [ ] T-01: `SyncPage` — пять вкладок (`tablist`/`tab`, `aria-selected`), вкладка по умолчанию «Источники»
  - Origin: `access/admin-ui/src/pages/SyncPage.tsx:8-39`
  - Критерий готовности: переключение показывает соответствующий вид; состояние не хранится в адресе
  - Уверенность: 🟢
- [ ] T-02: `health(s)` и `formatLag`
  - Origin: `access/admin-ui/src/pages/sync/SourcesTab.tsx:8-25`
  - Критерий готовности: AC-01, AC-02; `access/admin-ui/src/pages/SyncPage.test.tsx:79-89`
  - Уверенность: 🟢 (поведение) / 🔴 (достижимость веток — Q-01)
- [ ] T-03: `SourcesTab` (таблица, автообновление 30 с, «Обновить», пустое/ошибка)
  - Origin: `access/admin-ui/src/pages/sync/SourcesTab.tsx:28-87`; `access/admin-ui/src/hooks/useAsync.ts:19-56`
  - Критерий готовности: AC-03
  - Уверенность: 🟢
- [ ] T-04: `EventsTab` (фильтры, `toIso`, сброс страницы, пагинация 50)
  - Origin: `access/admin-ui/src/pages/sync/EventsTab.tsx:48-129`; `access/admin-ui/src/components/common.tsx:34-59, 101-106`
  - Критерий готовности: AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-05: `EventDetailCard` (поля события, причина, `historyNote`, записи DLQ; без payload)
  - Origin: `access/admin-ui/src/pages/sync/EventsTab.tsx:10-45`
  - Критерий готовности: AC-06
  - Уверенность: 🟢
- [ ] T-06: `DlqTab` (`replayed=false` по умолчанию, переключатель, фильтр источника)
  - Origin: `access/admin-ui/src/pages/sync/DlqTab.tsx:11-81`
  - Критерий готовности: AC-07
  - Уверенность: 🟢 (поведение) / 🔴 (смысл «открытые» — Q-02)

## Tarefas de Teste

- [ ] TT-01: `access/admin-ui/src/pages/SyncPage.test.tsx` — 16 тестов (из них 11 относятся к этому use case: источники ×5, журнал ×4, DLQ ×2)
- [ ] TT-02: 🔴 нет теста связи светофора с реальным `SyncRun` проектора (Q-01)
- [ ] TT-03: 🟡 фикстуры используют `source: 'urn:corp:eam'`, реальный API отдаёт короткий код (Q-18)

## Ordem Sugerida
T-02 → T-03 → T-04 → T-05 → T-06 → T-01.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-01, Q-02 (🔴); Q-09, Q-17, Q-18 (🟡).
