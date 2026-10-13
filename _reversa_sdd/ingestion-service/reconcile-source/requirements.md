# reconcile-source: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Сторона адаптера: [`../../adapter-core/full-snapshot/requirements.md`](../../adapter-core/full-snapshot/requirements.md).

## Visão Geral

`POST /admin/reconcile/{source}` вручную запускает полный snapshot источника в его adapter-сервисе (`POST <control-url>`). Сам модуль ничего не удаляет и не сверяет: удаления делает `Reconciler` проектора, когда диспетчер доберётся до маркера `snapshot-complete`.

## Regras de Negócio

- `source` — короткий код (`eam`, `scm`, `cmdb`, `deploymap`) или `urn:corp:<код>`; префикс `urn:corp:` отбрасывается 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:33-35`).
- Код после нормализации должен соответствовать `[A-Za-z0-9:._-]{1,200}`, иначе `400 source is invalid` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:103-106`).
- Нет настроенного непустого `archrag.adapters.<код>.control-url` → `404 {"error":"unknown source"}`; аудит не пишется, замок не берётся 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:107-110`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:42-48`).
- Вызов: `POST` без тела, `connectTimeout 5 с`, таймаут запроса 10 минут 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:20, 23, 60`).
- Статусы адаптера `202` и `409` пробрасываются клиенту вместе с телом как есть, `Content-Type: application/json`; любой другой статус, `IOException` и прерывание → `AdapterUnavailableException` → `502 {"error":"adapter is unavailable"}` (адрес и тело в сообщение не попадают) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:62-71`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:114, 130-133`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterUnavailableException.java:3-8`).
- Операция выполняется под `AdminLock` и пишется в аудит (`RECONCILE`, `request={"source":"<код>"}`, `result={"adapterStatus":<статус>}`); ответ адаптера `409 ALREADY_RUNNING` аудируется как `SUCCEEDED` 🟢/🟡 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:111-113`).
- Пока snapshot идёт, замок удерживается (до 10 минут), диспетчер и другие админские операции не работают 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:45`) — Н-12 в `../../tech-debt.md:67`.
- 🔴 `/control/snapshot` адаптера не аутентифицируется; защита только сетевая (П-15, `../../detective-gaps.md:44`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Прокси запуска snapshot с пробросом `202`/`409` | Should | AC-01 |
| RF-02 | `404` для ненастроенного источника, `502` при недоступности/неожиданном статусе | Should | AC-02 |
| RF-03 | `400` для некорректного кода | Should | AC-03 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Performance | Ожидание синхронного snapshot до 10 минут | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:19-20` | 🟢 |
| Segurança | Сообщения исключений без адреса и тела ответа | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterUnavailableException.java:3` | 🟢 |
| Segurança | Управляющие адреса только во внутренней сети | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdaptersProperties.java:9` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01 (AdminControllerTest.reconcileProxiesToAdapterAndPassesStatusAsIs; AdapterControlClientTest.acceptedAndAlreadyRunningArePassedThrough; AdminEndpointIT.reconcileProxiesToAdapterControlContext)
Дано control-url для eam настроен, адаптер отвечает 202 {"outcome":"SNAPSHOT_COMPLETED"}
Когда POST /admin/reconcile/urn:corp:eam
Тогда клиент получает 202 и то же тело; в аудите RECONCILE/SUCCEEDED, result.adapterStatus = 202
Дано адаптер отвечает 409 {"outcome":"ALREADY_RUNNING"}
Когда POST /admin/reconcile/eam
Тогда клиент получает 409 с телом адаптера

# AC-02 (AdminControllerTest.reconcileUnknownSourceIs404AndUnavailableAdapterIs502; AdapterControlClientTest.otherStatusAndConnectionFailureAreUnavailable, unknownSourceIsRejected; AdminEndpointIT.reconcileUnknownSourceIs404UnreachableAdapterIs502AndScopeIsEnforced)
Дано control-url для nope не настроен
Когда POST /admin/reconcile/nope
Тогда 404, snapshot не вызывался
Дано адаптер недоступен или ответил 500/503
Когда POST /admin/reconcile/scm
Тогда 502 {"error":"adapter is unavailable"}, аудит FAILED с AdapterUnavailableException

# AC-03
Дано source = "bad source"
Когда POST /admin/reconcile/bad source
Тогда 400 (AdminControllerTest.reconcileUnknownSourceIs404AndUnavailableAdapterIs502)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-03 | Should | Ручной триггер (ADR 0013); штатные snapshot-ы идут по расписанию адаптера |
| Аутентификация вызова адаптера | Won't | Нет в коде (П-15) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java` | `AdapterControlClient` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdaptersProperties.java` | `AdaptersProperties` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterUnavailableException.java` | `AdapterUnavailableException` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java` | `reconcile` | 🟢 |
