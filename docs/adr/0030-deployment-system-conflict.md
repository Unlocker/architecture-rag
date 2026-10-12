# 0030. Конфликт связи: система EAM из chart против системы сервиса

- **Статус:** Принято
- **Дата:** 2026-10-12
- **Источник:** решение 3 фичи UNLOCKER-248 (владелец 2026-10-10); UNLOCKER-254 (H.5); [ADR 0029](0029-helm-chart-system-repo-commit.md) (решение 4: система служит проверкой); [ADR 0019](0019-identity-resolution-no-auto-merge.md); [ADR 0024](0024-open-source-id-format.md)
- **Кто принял:** архитектор по решению владельца

## Контекст

Chart несёт целочисленный `id` системы EAM (`Deployment.eamSystemId`). Сервис SCM связан с системой связью `DECOMPOSED_INTO` с ключом записи `(EAM, IT_SYSTEM, <id>)`. После перевода `eam-adapter` на `eam-api` (H.4a, ADR 0024) обе стороны используют один ключ, и значения сравнимы. `source_conflict` хранил только расхождение свойства одного узла (авторитетный и неавторитетный источник).

## Решение

- **Форма записи.** Та же таблица `source_conflict`, колонка `kind text NOT NULL DEFAULT 'PROPERTY'` (миграция `V7__source_conflict_kind.sql`, аддитивная, PK не меняется). `RELATION`: `gid` — Deployment, `property = 'eamSystemId'`, `dissent_*` — запись DEPLOYMAP, `dissent_value` — id из chart, `master_*` — запись EAM `IT_SYSTEM` системы сервиса, `master_value` — её `sourceId`. Жизненный цикл как у `PROPERTY`: строка не удаляется, при совпадении `RESOLVED`, при новом расхождении снова `OPEN` с прежним `opened_at`.
- **Порт.** `SourceConflicts.assertRelation(dissent, gid, property, dissentValue, master, masterValue)`: пустой `master` или равные значения закрывают строку, расхождение делает upsert `OPEN`. `retract(key, gid)` закрывает и `RELATION`-строки, где запись — dissent. Пересчёт `PROPERTY` строки `RELATION` не трогает.
- **Система сервиса — из графа.** `DeploymentLookup.eamSystemOfService`: активная `ITSystem` с открытой `DECOMPOSED_INTO` на сервис и активной записью EAM. Больше одной системы: пусто и WARN, конфликт не открывается.
- **Где проверяется** (`EventProcessor`, вне транзакции Neo4j для DEPLOYMAP): (a) событие развёртывания: сервис берётся из `HAS_DEPLOYMENT` этого события, маркеры `ASSERTS.conflicts` обновляются; (b) событие сервиса SCM: после успешной записи графа перепроверяются его развёртывания с `eamSystemId` (`chartDeploymentsOfService`), поэтому порядок прихода SCM и DeployMap не важен; (c) tombstone развёртывания (`retract`, в том числе из `Reconciler`). Без `eamSystemId` проверки нет, а прежний открытый конфликт закрывается.
- **Граф не меняется.** Привязка `HAS_DEPLOYMENT`/`DEPLOYED_FROM` остаётся как в H.2; авто-исправления нет (ADR 0019).
- **Консоль.** `kind` в `/api/identity/conflicts` (необязательное новое поле) и колонка «Вид».

## Рассмотренные варианты

| Вариант | Итог |
|---|---|
| Колонка `kind` в `source_conflict` | Выбран: одна таблица, один API, обратная совместимость |
| Отдельная таблица `relation_conflict` | Отклонён: второй API и консольный экран ради одного вида |
| Сравнение в маппере DeployMap | Отклонён: зависит от порядка прихода SCM, граф читается до записи |

## Последствия

- **Ограничения.** (1) Маркер `ASSERTS.conflicts` развёртывания, вызванный событием SCM (b), не пишется: запрос проектора относится к `gid` одной записи. Он обновится с ближайшим событием развёртывания; сам конфликт виден в `source_conflict` сразу. (2) Появление системы EAM после SCM-сервиса (отложенная связь `DECOMPOSED_INTO`) повторной проверки не запускает: она выполнится с ближайшим событием сервиса или развёртывания. (3) Tombstone сервиса открытые конфликты его развёртываний не закрывает. (4) Имя `eamSystemId` и PK строки совпадают с возможным `PROPERTY` того же ключа записи; сейчас свойства `eamSystemId` у авторитетного источника нет, коллизии нет.
- **Откат.** Колонка с default безвредна: код откатывается revert-ом, миграция остаётся.
- **Затронутый код:** `ingestion/identity-resolution`, `ingestion/graph-projector`, `ingestion/normalizer` (порт), `access/admin-console`, `access/admin-ui`.
