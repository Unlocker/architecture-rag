# load-crosswalk: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Логика `approve` и модель gid: [`../../identity-resolution/gid-mapping-crosswalk/requirements.md`](../../identity-resolution/gid-mapping-crosswalk/requirements.md).

## Visão Geral

`POST /admin/crosswalks` загружает оператором подтверждённые соответствия двух source-записей одной канонической сущности. Каждая пара обрабатывается отдельно: конфликт или невалидный элемент остальным не мешает.

## Regras de Negócio

- Тело — массив `CrosswalkItem{left{source,sourceType,sourceId}, right{...}, reason}` длиной 1..1000, иначе `400 items must contain 1..1000 elements` (до замка) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:31, 117-121`).
- `approvedBy` — `sub` токена, `approvedAt` — `Instant.now()`; в запросе не принимаются (поля в `CrosswalkItem` отсутствуют) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java:3-7`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:48`).
- Исход элемента: `APPLIED` (+`gid`), `CONFLICT` (`CrosswalkConflictException`: ключи уже на разных `gid`; `gid = null`), `INVALID` (`IllegalArgumentException | NullPointerException`: неизвестное имя `source` в `SourceSystemCode.valueOf` (регистр важен, допустимы `EAM, SCM, CMDB, DEPLOYMAP, MANUAL`), пустые `sourceType`/`sourceId`/`reason`, одинаковые ключи, `left`/`right` = `null`) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:48-54, 66-68`; `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceKey.java:8-14`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/Crosswalk.java:14-25`; `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceSystemCode.java:4-6`).
- Любое иное `RuntimeException` (например, сбой PostgreSQL): аудит элемента `FAILED` с классом, исключение уходит наружу — `500`; уже применённые элементы остаются применёнными, результат по ним клиент не получает 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:55-58`).
- Слияние двух существующих разных `gid` не делается (E3); узлы под новый `gid` переписывает следующий replay/rebuild 🟢 (Javadoc `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:17-20`).
- Операция идёт под `AdminLock` (`operations.locked`), но без общей записи аудита: на каждый элемент создаётся своя запись `CROSSWALK` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:122`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:45`).
- Аудит элемента: `request = {index, left, right, reason≤200}` (`left`/`right` — `KeyDto.toString()`); итог `{status, gid}`; `SUCCEEDED` только при `APPLIED`, `CONFLICT`/`INVALID` пишутся как `FAILED` с `error = NULL` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:59-63, 71-79`).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)] `identity_candidate.status` crosswalk не меняет (кандидат остаётся `OPEN`) — уже отмечено в `../../code-analysis.md:561`; см. Q-10.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Поэлементная загрузка с исходами `APPLIED/CONFLICT/INVALID` | Must | AC-01, AC-02 |
| RF-02 | Лимит 1..1000, `sub` как `approvedBy` | Must | AC-03 |
| RF-03 | Аудит на элемент | Should | AC-04 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Segurança | Только `architecture.admin` + `azp` allow-list | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:42` | 🟢 |
| Segurança | `reason` усекается до 200 символов в аудите | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:76-77` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (AdminEndpointIT.crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem)
Дано валидная пара EAM/IT_SYSTEM ↔ SCM/CATALOG_SYSTEM
Когда POST /admin/crosswalks
Тогда 200 [{index:0,status:"APPLIED",gid:<uuid>}], аудит CROSSWALK/SUCCEEDED

# AC-02 (там же)
Дано пара, ключи которой уже связаны с разными gid, и пара с пустым reason / неизвестным source
Когда POST /admin/crosswalks
Тогда для них CONFLICT и INVALID с gid = null, остальные элементы применены

# AC-03 (AdminControllerTest.crosswalksPassSubjectAsApprover)
Дано токен sub = alice
Когда POST /admin/crosswalks с одним элементом
Тогда CrosswalkService.load получает approvedBy = "alice"
Дано пустой массив (или > 1000 элементов)
Когда POST /admin/crosswalks
Тогда 400

# AC-04
Дано сбой identity.approve (не конфликт и не валидация)
Когда обрабатывается элемент
Тогда audit.finish(id, false, null, "<SimpleName>"), исключение пробрасывается (покрыто только кодом, теста нет) 🟡
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-02 | Must | Единственный путь ручного подтверждения идентичности (ADR 0019) |
| RF-03 | Should | Аудит операторских решений |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java` | `CrosswalkService` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java`, `CrosswalkResult.java` | DTO | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java` | `crosswalks` | 🟢 |
