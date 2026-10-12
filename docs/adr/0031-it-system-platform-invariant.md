# 0031. Инвариант платформы ИТ-системы: Conflict при расхождении с решением

- **Статус:** Принято
- **Дата:** 2026-10-12
- **Источник:** решения владельца 2026-10-10/11 (UNLOCKER-228, `docs/06-eam-integration.md`); UNLOCKER-259 (H.7); расширяет [ADR 0030](0030-deployment-system-conflict.md); [ADR 0019](0019-identity-resolution-no-auto-merge.md); [ADR 0020](0020-no-physical-delete.md); [ADR 0024](0024-open-source-id-format.md)
- **Кто принял:** архитектор по решению владельца

## Контекст

В иерархии «платформа → решение → система» у ИТ-системы есть `platform_id`, который обязан совпадать с `platform_id` её решения (`product_id`). Прямой связи «система → платформа» нет, поле служит проверкой.

## Решение

- **`platformId` — свойство узла.** `ITSystem.platformId` и `Solution.platformId` (`Long`, необязательные, > 0) читает `EamMapper` из `attrs.platform_id`. Сравнение идёт по свойствам, а не по рёбрам, поэтому не зависит от того, пришла ли платформа и не отложена ли `USES_PLATFORM`. Мастер обоих свойств — EAM; правила `IT_SYSTEM.platformId → EAM` и `SOLUTION.platformId → EAM` заданы в `AuthorityMatrix` явно (SCM тоже утверждает `ITSystem`). Нецелое значение — карантин `INVALID_PAYLOAD`.
- **Форма записи** — `source_conflict`, `kind = 'RELATION'` ([ADR 0030](0030-deployment-system-conflict.md)): `gid` — узел `ITSystem`, `property = 'platformId'`; dissent — запись EAM `IT_SYSTEM` со значением её `platformId`; master — запись EAM `SOLUTION` со значением её `platformId`. Нет `platformId` у системы, решение неизвестно или без `platformId` — пустой master, строка закрывается.
- **Порт `EamLookup`** (`graph-projector`, реализует `GraphProjector`): `solutionPlatform(solution)` и `systemsOfSolution(solution)`; только активные записи EAM и открытые связи (`validTo IS NULL`).
- **Где проверяется** (`EventProcessor`): (a) событие `IT_SYSTEM` до записи графа: ключ решения — из `USES_SOLUTION` этого же события, включая отложенную; маркеры `ASSERTS.conflicts` ставятся полным набором; (b) событие `SOLUTION` после успешной записи графа (не на `IGNORED_OLD_VERSION`, не на tombstone): перепроверяются все системы решения с новым `platformId`, поэтому порядок прихода системы и решения не важен; (c) tombstone `IT_SYSTEM`: существующий `retract` закрывает строку. Сбой хранилища в (a) и (b) — `RETRYING` / `SOURCE_CONFLICT_FAILED`, повтор безопасен.
- **Граф не меняется.** Строится по решению (`USES_SOLUTION` → `USES_PLATFORM`); авто-исправления нет (ADR 0019); строки конфликтов только закрываются (ADR 0020).
- **Миграции нет.** `source_conflict.kind` появился в V7. Живые узлы получат `platformId` со следующей версией записи EAM или при rebuild; до этого `platformId = null`, конфликтов нет.

## Последствия

- **Ограничения.** (1) Как и в ADR 0030, маркер `ASSERTS.conflicts` системы, вызванный событием решения (b), не пишется; он обновится с ближайшим событием системы, сам конфликт виден в `source_conflict` сразу. (2) Tombstone решения и поздний приход платформы перепроверки не запускают. (3) Если решение пришло с `platformId = null` после непустого, в графе остаётся прежнее значение (`null` не затирает известное), проверка (b) берёт его из графа. (4) Коллизии PK с `PROPERTY`-строкой (ограничение 4 ADR 0030) нет: у `platformId` один мастер (EAM), закреплённый явными правилами `AuthorityMatrix` и тестом `AuthorityMatrixTest.platformIdOfSystemAndSolutionBelongsToEamOnly`; пока EAM единственный мастер, запись EAM не бывает диссидентом `PROPERTY`-конфликта по нему. Имя `platformId` совпадает с узловым свойством, как `eamSystemId` в ADR 0030.
- **Откат.** Revert PR; свойства необязательны, старые узлы читаются как прежде.
- **Затронутый код:** `contracts/canonical-model`, `ingestion/normalizer`, `ingestion/graph-projector`, `ingestion/ingestion-service`.
