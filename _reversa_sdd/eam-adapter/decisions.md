# adapters/eam-adapter — решения

> Явные решения, зафиксированные в коде и `docs/`. Расхождения — в [`questions.md`](questions.md).

| ID | Решение | Основание | Evidence | Уверенность |
|---|---|---|---|---|
| D-1 | Один коннектор `EAM` из двух половин вместо двух адаптеров: `Reconciler` tombstone-ит все активные записи источника, не попавшие в snapshot-прогон | решение 2 UNLOCKER-255; ADR [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md); `docs/06-eam-integration.md` §«Заглушки и fixtures» | `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/CompositeEamConnector.java:13-22` | 🟢 |
| D-2 | Мастер-системы в PoC — заглушки; legacy-половина остаётся «долгом PoC» | ADR [0010](../../docs/adr/0010-master-systems-are-stubs.md) | `CompositeEamConnector.java:14-17` | 🟢 |
| D-3 | `sourceId` = целочисленный `id` EAM без префикса | решение владельца 2026-10-04; ADR [0024](../../docs/adr/0024-open-source-id-format.md) | `EamApiConnector.java:175, 182` | 🟢 |
| D-4 | «ИТ-решение» = `itproducts` (`SOLUTION_TYPE`) | `docs/06-eam-integration.md` §«Решения» п. 1 | `EamApiConnector.java:47-48` | 🟢 |
| D-5 | Исчезновение = логическое удаление через snapshot + reconcile; `DELETE` из коннектора не порождается | ADR 0020; `docs/06-eam-integration.md` п. 4 | `EamApiConnector.java:182-183, 131` | 🟢 |
| D-6 | Инкремент по `sort=-mtime` до водяного знака (в API нет фильтра по времени) | `docs/06-eam-integration.md` §«Схема синхронизации» п. 1 | `EamApiConnector.java:31-35, 107, 115-118` | 🟢 |
| D-7 | Лимиты: `page_size ≤ 500`, ≤ 10 запросов/с, одно ожидание по `Retry-After` | `docs/06-eam-integration.md` п. 6 | `EamApiConnector.java:37-39, 45, 56, 205-219` | 🟢 |
| D-8 | Чужой курсор → перечитывание как инкремент без маркера и удалений (переход со старого адаптера) | UNLOCKER-255; `docs/06-eam-integration.md` §«Переход живого стенда» | `CompositeEamConnector.java:72-85` | 🟢 |
| D-9 | Токен берётся из env/secret (`ARCHRAG_EAM_API_TOKEN`) и не светится | правило проекта (секреты); `docs/06-eam-integration.md` п. 8 | `EamAdapter.java:49-52`, `LauncherConfig.java:137` | 🟢 |
| D-10 | Адаптер только читает; `GET` — единственный метод; буферы/`mrelations`/`mcp` не используются | `docs/06-eam-integration.md` §«Запись и согласование», п. 7 | `EamApiConnector.java:228` | 🟢 |
