# identity-audit: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 10..12), §6. Доменная часть: [`../../domain.md`](../../domain.md), [`../../state-machines.md`](../../state-machines.md) (жизненный цикл конфликта и кандидата), [`../../identity-resolution/design.md`](../../identity-resolution/design.md).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `IdentityTab` | `() -> JSX` | композиция `Conflicts` + `Candidates` | `access/admin-ui/src/pages/sync/IdentityTab.tsx:106-113` 🟢 |
| `Conflicts` | `() -> JSX` | состояние `status` (`OPEN` по умолчанию), `page`; `api.conflicts({status, page, size: 50})` | `access/admin-ui/src/pages/sync/IdentityTab.tsx:11-61` 🟢 |
| `Candidates` | `() -> JSX` | то же для `api.candidates` | `access/admin-ui/src/pages/sync/IdentityTab.tsx:63-103` 🟢 |
| `AuditTab` | `() -> JSX` | `page`; `api.audit({page, size: 50})` | `access/admin-ui/src/pages/sync/AuditTab.tsx:10-51` 🟢 |

## Fluxo Principal

1. Вкладка «Identity»: оба блока монтируются одновременно и загружают данные независимо (два запроса: `/api/identity/conflicts?status=OPEN&page=0&size=50` и `/api/identity/candidates?status=OPEN&page=0&size=50`) (`access/admin-ui/src/pages/sync/IdentityTab.tsx:15, 67`).
2. `Select` статуса создан с `allowAll={false}` и опциями `OPEN`, `RESOLVED`; смена значения → `setStatus` + `setPage(0)` → перезагрузка (`access/admin-ui/src/pages/sync/IdentityTab.tsx:9, 19, 71`; `access/admin-ui/src/components/common.tsx:72-99`).
3. Строки конфликтов: `gid` в `<code>`, вид по `kind === 'RELATION'`, значения `dissentValue`/`masterValue` через `text()` (`null` → «—») (`access/admin-ui/src/pages/sync/IdentityTab.tsx:38-50`).
4. Строки кандидатов: `matched ?? []` → `feature=value` через `text()` и запятую; пусто → «—» (`access/admin-ui/src/pages/sync/IdentityTab.tsx:91`).
5. Вкладка «Аудит»: `api.audit({page, size: 50})`; строки — время, операция, инициатор, статус, ошибка, признак усечения; `Pager` управляет страницей (`access/admin-ui/src/pages/sync/AuditTab.tsx:13-45`).

## Fluxos Alternativos

- Пустая страница → текст по секции (см. требования); ошибка → `role="alert"` (`access/admin-ui/src/components/common.tsx:28-30`).
- Статус `RESOLVED` у кандидатов → сервер вернёт пустую страницу, поскольку значение не выставляется нигде (Q-03) 🟡.
- Ответ без поля `kind` (старый формат) → «свойство» 🟢 (`access/admin-ui/src/pages/sync/IdentityTab.tsx:42`; `access/admin-ui/src/api/types.ts:162-163`).
- `request`/`result` в ответе аудита игнорируются UI; признак усечения берётся из `requestTruncated`/`resultTruncated` 🟢 (`access/admin-ui/src/pages/sync/AuditTab.tsx:40`).

## Dependências

REST консоли: `GET /api/identity/conflicts`, `/candidates`, `GET /api/audit` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:30-48`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:25-32`). Данные PostgreSQL: `source_conflict`, `identity_candidate`, `admin_audit` — читаются ролью `archrag_console_ro` (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-16`). Производители данных: [`../../identity-resolution/design.md`](../../identity-resolution/design.md), [`../../ingestion-service/design.md`](../../ingestion-service/design.md) (`admin_audit`).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Селектор статуса без «все»: в обеих секциях только `OPEN`/`RESOLVED` | `access/admin-ui/src/pages/sync/IdentityTab.tsx:19, 71` | 🟢 |
| В аудите показывается признак усечения, а не содержимое `request`/`result` | `access/admin-ui/src/pages/sync/AuditTab.tsx:40` | 🟢 |
| Составной ключ строки вместо суррогатного id | `access/admin-ui/src/pages/sync/IdentityTab.tsx:38, 87` | 🟢 |

## Riscos e Lacunas

- 🟢 [UNLOCKER-282: реализовано] Кандидаты `RESOLVED` достижимы через `POST /admin/identity/candidates/approve` (admin REST ingestion); в истории «Кандидаты» при `RESOLVED` показываются «Закрыт» (`resolvedAt`) и «Кем» (`resolvedBy`), тест `SyncPage.test.tsx`. Кнопки «Подтвердить» в UI нет: консоль read-only (ADR 0026) — вопрос архитектору.
- 🟡 Закрыть конфликт вручную нельзя (П-22), поэтому `OPEN`-конфликты копятся без операции разбора — `../../detective-gaps.md:51`.
- 🟡 Аудит чтений консоли не виден в UI и существует только как лог — Q-15.
