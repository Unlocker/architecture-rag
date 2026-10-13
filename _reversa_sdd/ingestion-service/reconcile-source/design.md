# reconcile-source: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракт REST: [`../contracts.md`](../contracts.md) §1, конфигурация §4. Сторона адаптера (`/control/snapshot`, `WebhookServer`): [`../../adapter-core/full-snapshot/design.md`](../../adapter-core/full-snapshot/design.md).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `AdapterControlClient.code` | `static (String source) -> String` | `source` без префикса `urn:corp:` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:33-35` 🟢 |
| `AdapterControlClient.knows` | `(String source) -> boolean` | есть непустой `control-url` | `:38-40` 🟢 |
| `AdapterControlClient.snapshot` | `(String source) -> Response(int status, String body)` | только `202`/`409`; иначе `AdapterUnavailableException`; `IllegalArgumentException("unknown source")` при отсутствии адреса | `:56-72` 🟢 |
| `AdaptersProperties(Map<String, Adapter>)` | `@ConfigurationProperties("archrag")` → ключи `archrag.adapters.<код>.control-url` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdaptersProperties.java:11-20` 🟢 |

## Fluxo Principal

1. `AdminController.reconcile`: `code = AdapterControlClient.code(source)`; не по `SOURCE`-шаблону → `BadRequestException("source is invalid")` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:101-106`).
2. `!adapters.knows(code)` → `404 {"error":"unknown source"}` (без замка и аудита) (`:107-110`).
3. `operations.run("RECONCILE", sub, {"source": code}, null, () -> adapters.snapshot(code), resp -> {"adapterStatus": resp.status()})` (`:111-113`).
4. `AdapterControlClient.snapshot`: `HttpRequest.newBuilder(uri).timeout(10 min).POST(noBody())`, `BodyHandlers.ofString()`; статус не 202/409 → `AdapterUnavailableException` (`:56-65`).
5. Ответ: `ResponseEntity.status(r.status()).contentType(application/json).body(r.body())` (`:114`).
6. Позже: адаптер дописывает события snapshot и маркер `snapshot-complete` в журнал → диспетчер ([`../dispatch-journal/design.md`](../dispatch-journal/design.md)) → `EventProcessor` → `Reconciler` ставит tombstone (вне модуля).

## Fluxos Alternativos

- `IOException` → `AdapterUnavailableException`; `InterruptedException` → `Thread.currentThread().interrupt()` + `AdapterUnavailableException` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:66-71`) 🟢.
- `AdapterUnavailableException` из тела операции → `AdminOperations` пишет `FAILED` (`error="AdapterUnavailableException"`) → `502` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:51-55`) 🟢.
- Замок занят → `409 another admin operation is in progress` (тот же код, что и `409 ALREADY_RUNNING` адаптера; различимы по телу) 🟡.

## Dependências

Конфигурация адаптеров (`ARCHRAG_*_CONTROL_URL`), `AdminOperations`, JDK `HttpClient` (один экземпляр на бин, `connectTimeout 5s`) (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:23`).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Reconcile = новый snapshot адаптера, а не сверка в ingestion | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:12-15` (Javadoc) | 🟢 |
| Ответ адаптера отдаётся «как есть» (статус и тело) | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:94-97, 114` | 🟢 |
| Ключи `adapters` — короткие коды, а не `SourceSystemCode` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdaptersProperties.java:7-10` | 🟢 |

## Riscos e Lacunas

- 🔴 `POST /control/snapshot` не аутентифицируется (П-15; `docs/03-interfaces.md:33`): защита сетевая.
- 🟡 Н-12: замок на всё время snapshot (до 10 мин) блокирует диспетчер и админские операции.
- 🟡 `409 ALREADY_RUNNING` аудируется как `SUCCEEDED` (аудит не отличает «запущено» от «уже шло»).
- 🟡 `AdapterControlClient.snapshot` не повторяет вызов; повтор — вручную.
