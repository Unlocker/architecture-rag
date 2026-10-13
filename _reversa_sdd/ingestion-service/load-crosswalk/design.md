# load-crosswalk: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракт REST: [`../contracts.md`](../contracts.md) §1. `IdentityMapping.approve`: [`../../identity-resolution/gid-mapping-crosswalk/design.md`](../../identity-resolution/gid-mapping-crosswalk/design.md).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `CrosswalkService.load` | `(List<CrosswalkItem>, String approvedBy) -> List<CrosswalkResult>` | по результату на элемент, в порядке запроса | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:35-41` 🟢 |
| `CrosswalkItem` | `record(KeyDto left, KeyDto right, String reason)`; `KeyDto(String source, String sourceType, String sourceId)` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java:7-10` 🟢 |
| `CrosswalkResult` | `record(int index, Status status, UUID gid)`; `Status ∈ {APPLIED, CONFLICT, INVALID}` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkResult.java:12-19` 🟢 |
| `IdentityMapping.approve` | `(Crosswalk) -> UUID` | `CrosswalkConflictException` при разных `gid` | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityMapping.java:26-33` 🟢 |

## Fluxo Principal

1. `AdminController.crosswalks` проверяет размер списка и вызывает `operations.locked(() -> crosswalk.load(items, jwt.getSubject()))` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:117-123`).
2. Для `i = 0..n-1` (последовательно, не в транзакции) `loadOne` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:43-64`):
   1. `request = describe(i, item)` — `{index, left, right, reason (≤200)}`; `item.left()==null` обрабатывается, `item == null` нет (NPE до `try`) 🟡 (`:71-79`);
   2. `auditId = audit.start("CROSSWALK", approvedBy, request, null)` (сбой — исключение, обработка прекращается);
   3. `new Crosswalk(key(left), key(right), approvedBy, reason, Instant.now())`, где `key` = `SourceKey(SourceSystemCode.valueOf(source), sourceType, sourceId)` (`:66-68`);
   4. `identity.approve(crosswalk)` → `APPLIED(gid)`;
   5. `CrosswalkConflictException` → `CONFLICT`; `IllegalArgumentException | NullPointerException` → `INVALID`;
   6. иное `RuntimeException` → `audit.finish(auditId, false, null, SimpleName)` и `throw`;
   7. `audit.finish(auditId, status == APPLIED, {status, gid}, null)`.
3. Список результатов возвращается как `200` JSON.

## Fluxos Alternativos

- **Замок занят** → `409`, ни один элемент не обработан (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:72-76`).
- **Сбой `audit.finish` после `APPLIED`** не перехватывается (в отличие от `AdminOperations.finishQuietly`): исключение прерывает пакет, клиент получает `500` без результатов, применённые элементы остаются 🟡 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:62`).
- **Идемпотентность**: повторная загрузка той же пары даёт `APPLIED` с тем же `gid` (контракт `approve`) 🟢 (`IdentityMapping.java:27-29`).

## Dependências

`IdentityMapping` (Postgres-реализация), `AdminAudit`, `canonical-model` (`SourceKey`, `SourceSystemCode`).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Аудит по элементам, а не одной записью на пакет | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:17-20, 45`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:122` | 🟢 |
| Невалидность и конфликт — результат, а не HTTP-ошибка | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:51-54` | 🟢 |
| `approvedBy = sub`, время серверное | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java:3-6` | 🟢 |

## Observabilidade

Логов в сервисе нет; след — только `admin_audit` (`operation = CROSSWALK`) 🟢.

## Riscos e Lacunas

- 🟡 `item == null` внутри массива даёт NPE в `describe` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:74`) → `500`, без записи аудита.
- 🟡 Пакет не атомарен; при сбое посреди пакета клиент не знает, что применено (смотреть `admin_audit`).
- 🔴 Q-10: `identity_candidate.status` не обновляется после `approve` (в модуле вызова нет).
