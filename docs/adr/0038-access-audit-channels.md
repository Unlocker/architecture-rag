# 0038. Аудит доступа: три независимых канала

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** UNLOCKER-278; кандидат К-8 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269); согласие владельца в UNLOCKER-260 (2026-10-13); реализация — UNLOCKER-170, UNLOCKER-180, UNLOCKER-237; доработки — UNLOCKER-280, UNLOCKER-285
- **Кто принял:** владелец, 2026-10-13

## Контекст

ADR [0013](0013-operator-ops-via-admin-endpoint.md) вводит админские операции, ADR [0005](0005-readonly-mcp-by-application.md) — доступ агентов через MCP, ADR [0026](0026-admin-console-readonly-loopback.md) описывает только аудит чтений консоли. Общего решения об аудите нет; хранение, срок и неизменяемость записей не определены.

## Решение

Как в коде сейчас, аудит идёт тремя независимыми каналами:

1. **Админские операции — таблица `admin_audit`** (`V3__admin_audit.sql`). Запись `STARTED` создаётся до операции, без неё операция не начинается; итог — `SUCCEEDED` или `FAILED` с классом исключения, текст исключения не пишется, потому что может нести данные источника (`ingestion/ingestion-service/.../AdminOperations.java:10-18`, `:45-56`; `AdminAudit.java:15`, `:33-56`).
2. **MCP — структурный лог каждого `tools/call`**, включая отказы: `principal`, tool, нормализованные аргументы, шаблоны, решение (`access/mcp-server/.../audit/ToolCallAuditor.java:101-141`). Аргументы нормализуются: строки и вложенность ограничены, значения по ключам `token|secret|password|authorization|credential` заменяются на `***` (`ToolArguments.java:20-26`); маскирование эвристическое, по именам ключей.
3. **Консоль — лог чтений** (`access/admin-console/.../audit/ReadAuditInterceptor.java:23`, `:53`), см. [ADR 0026](0026-admin-console-readonly-loopback.md).

Значения из источников в аудит целиком не попадают.

Реализовано в UNLOCKER-280 (пп. 4–5) и UNLOCKER-285 (п. 6):

4. Глубина хранения `admin_audit` задаётся параметром `archrag.admin.audit-retention` (по умолчанию `90d`, `ingestion/ingestion-service/.../application.yml:26`, `AdminProperties.java:30`); записи старше удаляет `HousekeepingJob.cleanAudit` (`HousekeepingJob.java:141-153`).
5. Записи `STARTED`, оставшиеся после падения процесса или сбоя записи итога, переводятся в `FAILED` с `error = 'abandoned'` через `archrag.admin.audit-stale-after` (по умолчанию `24h`, `application.yml:24`; `HousekeepingJob.java:144-148`). Сбой записи итога после успешной операции по-прежнему только логируется, клиенту ошибка не отдаётся (`AdminOperations.java:56-67`); запись закроет чистка.
6. Аудит отказов `ToolScopeFilter`: помимо `auditor.denied` для неизвестного tool и недостающего scope (`access/mcp-server/.../security/ToolScopeFilter.java:95`, `:100`), отказы `400` и `413` до разбора тела пишутся ровно одной записью через `auditor.rejected` с решениями `REJECTED_INVALID_REQUEST` и `REJECTED_TOO_LARGE` (`ToolScopeFilter.java:65`, `:73`, `:81`, `:86`; `audit/ToolCallAuditor.java:103`, `audit/ToolDecision.java:12-14`).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Три канала по природе доступа | Каждый канал проще; `STARTED` до операции гарантирует след | Разные места и сроки хранения; MCP и консоль живут в stdout | Выбран для PoC |
| B. Единая таблица аудита для всех каналов | Один запрос, один срок хранения | Запись в PostgreSQL на каждый MCP-вызов; MCP не зависит от PostgreSQL | Отложен |
| C. Внешняя система аудита | Неизменяемость, срок | Вне PoC | Production |

## Последствия

- **Положительные:** админская операция без следа невозможна; секреты и тексты ошибок не попадают в аудит.
- **Отрицательные / ограничения:** логи MCP и консоли не хранятся и не защищены от изменения; срок хранения `admin_audit` ограничен настройкой, а брошенные `STARTED` закрываются только по `audit-stale-after` (пп. 4–5); единого представления аудита нет.
- **Затронутый код:** `ingestion/ingestion-service` (`AdminOperations`, `AdminAudit`), `access/mcp-server` (`ToolCallAuditor`, `ToolArguments`, `ToolScopeFilter`), `access/admin-console` (`ReadAuditInterceptor`).

## Связанные ADR

[0005](0005-readonly-mcp-by-application.md) — MCP. [0013](0013-operator-ops-via-admin-endpoint.md) — админские операции. [0026](0026-admin-console-readonly-loopback.md) — аудит консоли.
