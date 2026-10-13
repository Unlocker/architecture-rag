# identity-audit: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 10..12). Соседние вкладки раздела — [`../sync-monitoring/requirements.md`](../sync-monitoring/requirements.md).

## Visão Geral

Две вкладки раздела «Синхронизация»: «Identity» (конфликты источников и кандидаты на совпадение) и «Аудит» (журнал админ-операций ingestion). Обе только читают через `GET /api/identity/*` и `GET /api/audit`; закрыть конфликт, подтвердить кандидата или повторить операцию из UI нельзя 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:106-113`, `access/admin-ui/src/pages/sync/AuditTab.tsx:10-51`; ADR 0026: `docs/adr/0026-admin-console-readonly-loopback.md:18`).

## Regras de Negócio

- «Identity» показывает две секции — «Конфликты» и «Кандидаты»; у каждой свой селектор статуса `OPEN`/`RESOLVED` **без пункта «все»**, по умолчанию `OPEN`; смена статуса сбрасывает страницу 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:9, 13-19, 65-71`).
- Конфликт: `gid`, вид («свойство» либо «связь» при `kind = 'RELATION'`; отсутствующий `kind` у старых ответов = «свойство»), свойство, расходящийся источник `source/type/id` и значение, мастер `source/type/id` и значение, время открытия 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:23-50`; `access/admin-ui/src/api/types.ts:162-163`).
- Кандидат: левая и правая запись `source/type/id`, семейство меток, совпавшие признаки `feature=value` через запятую (или «—», если список пуст/`null`), score 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:75-96`).
- Ключ строки составной (для конфликта — `gid|property|dissentSource|dissentType|dissentId`, для кандидата — шесть полей пары) 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:38, 87`).
- «Аудит»: страница 50; колонки «Начало», «Операция», «Инициатор» (`actor` или «—»), «Статус», «Ошибка» (или «—»), «Данные» — бейдж «обрезано», если `requestTruncated` или `resultTruncated`; `request` и `result` в таблице **не** показываются 🟢 (`access/admin-ui/src/pages/sync/AuditTab.tsx:7, 19-44`).
- Пустые списки: «Конфликтов с таким статусом нет», «Кандидатов с таким статусом нет», «Операций нет» 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:20, 72`; `access/admin-ui/src/pages/sync/AuditTab.tsx:16`).
- Сервер по умолчанию подставляет `status = OPEN`; UI всегда шлёт статус явно 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:29, 37, 40, 48`; `access/admin-ui/src/api/endpoints.ts:65-68`).
- UI не передаёт фильтры `gid`, `labelFamily` (identity) и `operation`, `status`, `from`, `to` (audit), хотя бэкенд их поддерживает 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:30-35, 41-46`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:25-32`).
- 🔴 `identity_candidate.status` не выходит из `OPEN`: селектор `RESOLVED` для кандидатов всегда пуст, операции закрытия у оператора нет (Q-03).
- 🟡 Аудит, который видит оператор, — только `admin_audit` ingestion (replay/rebuild/reconcile/crosswalk); чтения самой консоли пишутся в лог сервера и в UI не видны (Q-15).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Список конфликтов с фильтром статуса и пагинацией | Should | AC-01, AC-03 |
| RF-02 | Список кандидатов с фильтром статуса и пагинацией | Should | AC-01, AC-03 |
| RF-03 | Различие конфликтов свойства и связи | Should | AC-02 |
| RF-04 | Журнал админ-операций постранично, признак «обрезано» | Should | AC-04, AC-05 |
| RF-05 | Пустое состояние и ошибка для обеих вкладок | Must | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Безопасность | Значения конфликтов, признаки и ошибки — как текст | `access/admin-ui/src/pages/sync/IdentityTab.tsx:45, 47, 91`; `access/admin-ui/src/pages/sync/AuditTab.tsx:37-39` | 🟢 |
| Безопасность | `request`/`result` аудита не выводятся (могут содержать данные запроса); UI показывает только признак усечения | `access/admin-ui/src/pages/sync/AuditTab.tsx:40`; `access/admin-ui/src/api/types.ts:186-199` | 🟢 |
| Производительность | Размер страницы 50 (сервер: максимум 200) | `access/admin-ui/src/pages/sync/IdentityTab.tsx:7`; `access/admin-ui/src/pages/sync/AuditTab.tsx:7` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (SyncPage.test.tsx «identity shows conflicts and candidates»)
Дано два конфликта (свойство owner, связь eamSystemId) и кандидат с matched [{name, billing}]
Когда открыта вкладка «Identity»
Тогда в таблице конфликтов видны «owner» и «eamSystemId», в таблице кандидатов — «name=billing»

# AC-02 (SyncPage.test.tsx «identity shows conflicts and candidates»)
Дано конфликт с kind = 'RELATION' и конфликт с kind = 'PROPERTY'
Тогда в колонке «Вид» — «связь» и «свойство» соответственно

# AC-03 (SyncPage.test.tsx «identity status filter requests RESOLVED history»)
Дано вкладка открыта: запрос /api/identity/conflicts?status=OPEN&page=0&size=50
Когда выбран статус RESOLVED у конфликтов и у кандидатов
Тогда запросы /api/identity/conflicts?status=RESOLVED&page=0&size=50 и /api/identity/candidates?status=RESOLVED&page=0&size=50

# AC-04 (SyncPage.test.tsx «audit shows admin operations»)
Дано одна запись аудита: operation reconcile, actor admin
Когда открыта вкладка «Аудит»
Тогда в таблице «Аудит админ-операций» видны «reconcile» и «admin»

# AC-05 (SyncPage.test.tsx «audit marks truncated records»)
Дано две записи: у первой resultTruncated = true, у второй false
Тогда бейдж «обрезано» ровно один

# AC-06 (SyncPage.test.tsx «audit shows empty state»; негатив — ошибка загрузки)
Дано items = []
Тогда «Операций нет»
Дано GET /api/audit завершился 500
Тогда role=alert «Ошибка запроса (HTTP 500)»                       # поведение общего useAsync; отдельного теста для audit нет 🟡
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Конфликты, кандидаты, аудит (RF-01..RF-04) | Should | Диагностика, не критично для корректности |
| Подтверждение/закрытие конфликтов и кандидатов, ручной crosswalk | Won't | Нет write-путей в консоли (ADR 0026); `MANUAL` не авторитетен (П-22 `../../detective-gaps.md:51`) |
| Фильтры по `gid`, `operation`, `status`, периоду | Could | Бэкенд поддерживает, UI не использует |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-ui/src/pages/sync/IdentityTab.tsx` | `IdentityTab`, `Conflicts`, `Candidates` | 🟢 |
| `access/admin-ui/src/pages/sync/AuditTab.tsx` | `AuditTab` | 🟢 |
| `access/admin-ui/src/pages/SyncPage.test.tsx` | 5 тестов вкладок Identity/Аудит | 🟢 |
