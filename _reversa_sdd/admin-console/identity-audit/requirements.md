# identity-audit: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Производители данных: `ingestion/identity-resolution` (`source_conflict`, `identity_candidate`), `ingestion/ingestion-service` (`admin_audit`).

## Visão Geral

Оператор просматривает идентичность и аудит админских операций: конфликты значений источников (`source_conflict`), кандидаты на совпадение (`identity_candidate`) и журнал операций ingestion (`admin_audit`: replay, rebuild, reconcile, crosswalk). Все три эндпоинта — `GET` со страницами, чтение PostgreSQL ролью `archrag_console_ro`. Исправить конфликт или подтвердить кандидата из консоли нельзя (операции — в admin REST ingestion).

## Regras de Negócio

- **conflicts**: фильтры `status` (по умолчанию `OPEN`; `RESOLVED` — история) и `gid` (UUID, нормализуется); сортировка `updated_at DESC, gid, property, dissent_*`; поле `kind` (`PROPERTY` или `RELATION`) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:30-38, 65-74`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:172-200`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:347`).
- **conflicts**: значения `dissentValue` и `masterValue` режутся до 500 символов 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:190, 194, 338-340`).
- **candidates**: фильтры `status` (по умолчанию `OPEN`) и `labelFamily`; сортировка `score DESC, updated_at DESC, ...`; `matched` — JSON-массив `{feature, value}` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:41-49`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:203-228`).
- `status`, `labelFamily`, `operation` — токены `[A-Za-z_]{1,64}`; иное → `400 <имя> is malformed`; пустое значение = «фильтр не задан» (или значение по умолчанию) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:21, 51-63`).
- **audit**: фильтры `operation`, `status`, `from`/`to` (`started_at >= from AND < to`, `from < to`, иначе 400); сортировка `started_at DESC, id DESC` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:25-39`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:233-248`).
- **audit**: `request` и `result` длиннее 4000 символов (по `length(...::text)`) не возвращаются: поле `null` и признак `requestTruncated`/`resultTruncated = true`; `error` режется до 500 символов 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:35-36, 241-264`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:240`).
- Страницы: `page ≥ 0`, `1 ≤ size ≤ 200`, по умолчанию 50; `total` — `count(*)` под тем же фильтром 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java:10-24`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:271-289`).
- `admin_audit.request`/`result` возвращаются дословно (без маскирования); безопасность содержимого — на стороне ingestion 🟡 (Q-16).
- `identity_candidate.status` не выходит из `OPEN`, закрытие конфликта вручную невозможно: список «открытых» отражает это (Р-4, П-22) 🔴 вне модуля (`../../detective-gaps.md:22, 51`; Q-10 в `ingestion-service`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Список конфликтов источников с фильтрами | Must | AC-01, AC-03 |
| RF-02 | Список кандидатов на совпадение | Must | AC-02, AC-03 |
| RF-03 | Журнал админских операций | Must | AC-04, AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Чтение шести таблиц под RO-ролью; доступа к `identity_mapping`, `identity_feature`, `property_assertion` нет | `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15`; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:183` | 🟢 |
| Безопасность | Значения фильтров только параметрами; токены ограничены шаблоном | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:21`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:342-365` | 🟢 |
| Безопасность | Тексты источников (значения конфликтов) обрезаны, не интерпретируются | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:338-340` | 🟢 |
| Производительность | Размер jsonb аудита ограничен 4000 символов в ответе | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:36, 242-245` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (SyncConsoleIT.conflictsAndCandidatesAreListedAndFiltered)
Дано один открытый конфликт по свойству criticality (kind=PROPERTY) и один закрытый (kind=RELATION)
Когда GET /api/identity/conflicts
Тогда total = 1, property = criticality
Когда GET /api/identity/conflicts?status=RESOLVED&gid=<UUID закрытого>
Тогда total = 1, kind = RELATION

# AC-02 (SyncConsoleIT.conflictsAndCandidatesAreListedAndFiltered)
Дано один кандидат OPEN со score 0.9 и признаком name
Когда GET /api/identity/candidates?status=OPEN
Тогда total = 1, matched[0].feature = name
Когда GET /api/identity/candidates?status=REJECTED
Тогда total = 0

# AC-03 (SyncApiTest.sizeAboveLimitAndNegativePageAreRejected, unknownSourceAndStatusAreRejectedWithoutEchoingInput)
Дано size=201 или page=-1 для /api/identity/conflicts, /candidates, /api/audit
Когда GET
Тогда 400
Дано gid=zzz
Когда GET /api/identity/conflicts?gid=zzz
Тогда 400 без эха

# AC-04 (SyncConsoleIT.auditListsOperationsWithRequestParameters)
Дано записи admin_audit bigop, rebuild, replay
Когда GET /api/audit
Тогда порядок bigop, rebuild, replay (started_at DESC)
Когда GET /api/audit?operation=replay
Тогда actor = alice, result.replayed = 2, request присутствует
Когда GET /api/audit?status=FAILED
Тогда total = 1

# AC-05 (SyncConsoleIT.oversizedAuditResultIsNotReturned)
Дано запись bigop с request и result по ~6000 символов
Когда GET /api/audit?operation=bigop
Тогда request = null, requestTruncated = true, result = null, resultTruncated = true
Дано запись replay с коротким result
Когда GET /api/audit?operation=replay
Тогда resultTruncated = false
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-03 | Must | Прозрачность идентичности и операций оператора (ADR 0026) |
| Подтверждение кандидата / закрытие конфликта | Won't | Нет в коде (Р-4, П-22; `docs/00-vision.md:399`) |
| Маскирование `admin_audit` | Won't | Нет в коде (Q-16) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java` | `IdentityController` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java` | `AuditController` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java` | `conflicts`, `candidates`, `audit` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java` | `Conflict`, `Candidate`, `Audit` | 🟢 |
