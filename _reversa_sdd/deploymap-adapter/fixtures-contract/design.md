# fixtures-contract, Design

Опирается на [../design.md](../design.md); формат и таблица fixtures — [../contracts.md](../contracts.md).

## Загрузка
- `load(name)` читает classpath-ресурс `/deploymap/<name>`, парсит `EamApiFormat.parse` (мини-JSON из `source-stubs`), приводит к `List<Map<String,Object>>`; отсутствие ресурса — assertion failure (`adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java:32-41`) 🟢.
- `single(name)` требует ровно одну запись (`:58-62`) 🟢.

## Источники согласованности
| Что сверяется | Откуда | Evidence |
|---------------|--------|----------|
| `url` репозитория | `StubSources.scm(CLOCK).fetchById(REPOSITORY, "repo-payments-api")` | `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java:64-68` 🟢 |
| `id` системы сервиса | `service.payload.systemCode` → `EamApiSeed.seeded(CLOCK).findIdByAttr(IT_SYSTEM_TYPE, "eam_id", systemCode)` | `:71-79` 🟢 |
| нормализация URL | `GitUrls.normalize` из `canonical-model` (test-scope) | `:98-101` 🟢 |
| seed deploy map | `StubSources.deployMap(CLOCK).fetchById(DEPLOYMENT, "dep-payments-api-prod")` | `:107-111` 🟢 |

`CLOCK` — `Clock.fixed(2026-10-04T12:00:00Z, UTC)` (`:27`) 🟢.

## Заметки
- Тесты чисто unit (surefire, имя `*Test`), без Docker/сети 🟢.
- Риск H.2: сравнение системы для `Conflict` идёт по индексу `eam_id → id` (ADR 0024, 0030); модуль гарантирует только согласованность seed (`README.md:20-23`) 🟡.
- Потребитель fixtures: нормализатор (`DeployMapMapper.java:142-190`) — логику разбора, коды ошибок (`INVALID_PAYLOAD`, `MISSING_REQUIRED_FIELD`) см. его спецификацию; fixtures из этого модуля им не читаются 🟡 (Q-04).
- Предыдущий анализ ссылается на README `:24-27` (риск H.2); в файле 23 строки, раздел находится в `README.md:20-23` (`_reversa_sdd/code-analysis.md:365`) 🟢.
