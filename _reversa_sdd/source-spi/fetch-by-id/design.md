# fetch-by-id — дизайн

> Модуль: [`../design.md`](../design.md). Диаграмма: `_reversa_sdd/flowcharts/source-spi.md` §2.

## Interface
`Optional<SourceChange> fetchById(String sourceType, String sourceId) throws SourceUnavailableException` (`SPI/SourceConnector.java:33`). `SourceChange` и `WebhookEvent` — [`../design.md`](../design.md), [`../contracts.md`](../contracts.md) §2-3.

## Fluxo Principal
Потребитель — `WebhookHandler.handle` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java`):
1. `WebhookSignature.verify` ≠ `VALID` → `401` (`:57-66`).
2. Разбор тела как JSON-объекта (иначе `400`), обязательные непустые строки `eventId`, `sourceType`, `sourceId`; `source` == `config.system().code()`; `eventId` == заголовок `X-Archrag-Event-Id` (иначе `400`) (`:68-82`).
3. `connector.fetchById(sourceType, sourceId)` (`:86`).
4. Найден → `change = state.get()` (`:91-92`).
5. Не найден и `operation == "DELETE"` в теле → tombstone из подписанного уведомления (`sourceVersion` число ≥ 0, иначе `400`) (`:93-100`).
6. Иначе `404` (`:102`).
7. `inbox.record(eventId, change, eventId, null)` → `202` (`:105-106`).

`HttpSourceConnector.fetchById`: `GET /objects/{enc(type)}/{enc(id)}`; `404` → `Optional.empty()`; прочее ≠ 200 → `IllegalStateException`; `429`/≥500/`IOException` → `SourceUnavailableException` (`AC/HttpSourceConnector.java:70-77, 90-100`) 🟢.

## Fluxos Alternativos
- **Источник недоступен:** `SourceUnavailableException` → `503` (`WebhookHandler.java:85-89`) 🟢.
- **Неизвестный объект при UPSERT:** `404` 🟢.
- **Дубль `eventId`:** `inbox.record` возвращает управление, ответ `202` (`docs/03-interfaces.md:349`) 🟡.

## Dependências
`WebhookSignature` (шаг 1), `InboxWriter` (вне SPI).

## Decisões de Design Identificadas
| Решение | Evidence | Уверенность |
|---|---|---|
| Состояние берётся у источника, а не из тела webhook | `SPI/WebhookEvent.java:5-7` | 🟢 |
| Для удалённого объекта допускается два пути: `DELETE` от источника или tombstone из подписанного тела | `SPI/SourceConnector.java:27-28`; `WebhookHandler.java:93-100` | 🟢 |
| `payload` копируется в `LinkedHashMap`, чтобы сохранить `null` | `SPI/SourceChange.java:39-41` | 🟢 |

## Estado Interno
Нет.

## Observabilidade
Нет.

## Riscos e Lacunas
- 🟡 Tombstone из тела использует `clock.instant()` как `updatedAt`, а не `occurredAt` (`WebhookHandler.java:99-100`).
- 🟡 `sourceVersion` у `SourceChange` — `long`; в событии — `SourceVersion` (до 40 цифр) (Q-05).
- 🔴 Инварианты `SourceChange` не принуждаются (Q-02); нет тестов DTO (Q-03).
