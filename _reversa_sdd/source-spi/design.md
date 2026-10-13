# contracts/source-spi — дизайн

> Сокращения: `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`. Метки 🟢/🟡/🔴. Требования — [`requirements.md`](requirements.md).

## Interface

Пакет `io.github.unlocker.archrag.sourcespi` (10 файлов main, 1 тестовый класс) 🟢.

| Символ | Сигнатура | Возврат | Наблюдение |
|--------|-----------|---------|-----------|
| `SourceConnector.system` | `()` | `SourceSystem` | какой источник обслуживает коннектор (`SPI/SourceConnector.java:15`) |
| `SourceConnector.fetchChanges` | `(String cursor, int limit)` | `ChangePage` | `cursor == null` — snapshot с начала; `limit` — положительное число (контрактом, не проверкой); `throws SourceUnavailableException` (`:24`) |
| `SourceConnector.fetchById` | `(String sourceType, String sourceId)` | `Optional<SourceChange>` | пусто — источник объект не знает; удалённый объект → `ChangeOperation.DELETE` (`:33`) |
| `SourceChange` | `record(sourceType, sourceId, long sourceVersion, ChangeOperation operation, Completeness completeness, Instant updatedAt, Map<String,Object> payload)` | — | `NPE` на `null` у `sourceType/sourceId/operation/completeness/updatedAt`; `payload` → неизменяемая копия `LinkedHashMap` (порядок сохраняется), `null` → `Map.of()` (`SPI/SourceChange.java:24-42`) |
| `ChangePage` | `record(List<SourceChange> changes, String nextCursor, boolean hasMore, boolean snapshotComplete)` | — | `List.copyOf(changes)` (`null` → NPE, `null`-элемент → NPE); `snapshotComplete && hasMore` → IAE (`SPI/ChangePage.java:17-25`) |
| `WebhookEvent` | `record(String eventId, String source, String sourceType, String sourceId, long sourceVersion, ChangeOperation operation, Instant occurredAt)` | — | без валидации; `source` — `String` (код, не `SourceSystem`) (`SPI/WebhookEvent.java:18-25`) |
| `ChangeOperation` | enum | `UPSERT`, `DELETE` | `SPI/ChangeOperation.java:4-7` |
| `Completeness` | enum | `COMPLETE`, `PARTIAL` | `SPI/Completeness.java:7-10` |
| `SourceSystem` | enum + `code()` | `EAM("eam")`, `SCM("scm")`, `CMDB("cmdb")`, `DEPLOY_MAP("deploymap")` | `SPI/SourceSystem.java:4-9, 18-20` |
| `SourceUnavailableException` | `(SourceSystem system, int statusCode, Duration retryAfter)`; `statusCode()`, `retryAfter()`, `isRateLimited()` | — | `RuntimeException`; message `system.code() + " unavailable, status=" + statusCode`; `system == null` → NPE (`SPI/SourceUnavailableException.java:18-36`) |
| `WebhookSignature.sign` | `static (String secret, long timestampSeconds, String body)` | `String` | `"sha256=" + lowercase-hex(HMAC)` (`SPI/WebhookSignature.java:47-49`) |
| `WebhookSignature.verify` | `static (String secret, String timestampHeader, String signatureHeader, String body, Instant now, Duration window)` | `WebhookSignature.Result` | см. алгоритм (`:58-86`) |
| `WebhookSignature.Result` | enum | `VALID`, `MALFORMED`, `BAD_SIGNATURE`, `OUT_OF_WINDOW` | `:36-42` |
| Константы | `SIGNATURE_HEADER="X-Archrag-Signature"`, `TIMESTAMP_HEADER="X-Archrag-Timestamp"`, `EVENT_ID_HEADER="X-Archrag-Event-Id"`, `PREFIX="sha256="` (private) | — | `:25, 28, 31, 33` |

## Fluxo Principal

Модуль — набор контрактов; поведение исполняемое только у `WebhookSignature` и конструкторов DTO.

1. Адаптер получает webhook → `WebhookSignature.verify(...)` ([`verify-webhook-signature/`](verify-webhook-signature/design.md)) 🟢 (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:57-66`).
2. После `VALID` адаптер читает состояние объекта `fetchById` ([`fetch-by-id/`](fetch-by-id/design.md)) 🟢 (`WebhookHandler.java:86`).
3. Периодически адаптер вызывает `fetchChanges` страницами ([`fetch-changes/`](fetch-changes/design.md)) 🟢 (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java:120-177, 195`).

## Fluxos Alternativos

- **Источник недоступен:** `SourceUnavailableException` (429/5xx/timeout) — повтор с backoff у `Poller`, `503` у `WebhookHandler` 🟢.
- **Невалидная подпись:** любой результат `verify` кроме `VALID` → вызывающая сторона отвечает `401` без тела 🟢 (`WebhookHandler.java:65-66`).

## Dependências

- **Maven:** `canonical-model` (`contracts/source-spi/pom.xml:19-21`). 🔴 В main и test нет ни одного `import io.github.unlocker.archrag.canonicalmodel` (проверено `grep`), т. е. зависимость не используется; документы `dependencies.md` (`SPI --> CM`) и `inventory.md:29` её фиксируют как рабочую.
- **JDK:** `javax.crypto.Mac`, `javax.crypto.spec.SecretKeySpec`, `java.security.MessageDigest`, `java.util.HexFormat` 🟢.
- Тест: JUnit 5 + AssertJ (из BOM корня; в `pom.xml` модуля тестовых зависимостей не объявлено — приходят из корневого `pom.xml:164-171`) 🟢.

## Зависит от / Связанные спецификации

(`traceability/spec-impact-matrix.md` §1 и §2.)

| Спецификация | Связь | Статус |
|---|---|---|
| `contracts/canonical-model` | прямая зависимость в `pom.xml`, не используется (§Dependências) | 🔴 |
| `adapters/adapter-core` | реализация порта `HttpSourceConnector` (`HttpSourceConnector.java:33`); `EamApiConnector` (`EamApiConnector.java:42`) и `CompositeEamConnector` (`CompositeEamConnector.java:27`) лежат в `adapters/eam-adapter`, `StubSource` (`StubSource.java:29`) — в `platform/source-stubs` `[Ревью R.7]`; потребляет весь SPI: `HttpSourceConnector`, `WebhookHandler` (verify), `Poller`, `EventMapper` (`long → SourceVersion`) — **меняется SPI ⇒ пересмотреть** | 🟢 (матрица §1) |
| `adapters/eam-adapter` (`EamApiConnector`), `scm-adapter`, `asset-adapter`, `deploymap-adapter` | реализации/конфигурация на `SourceConnector`; `SourceUnavailableException` бросает `EamApiConnector` (`EamApiConnector.java:216, 234`) | 🟢 |
| `platform/source-stubs` | `StubSource` реализует порт, `StubWebhookSender` подписывает через `WebhookSignature.sign` (`StubWebhookSender.java:47, 55, 76-78`) | 🟢 |
| `ingestion/normalizer` | зависимость на `source-spi` в `pom.xml`; в матрице отмечено «тест» | 🟡 |
| `ingestion/ingestion-service` | зависимость на `source-spi` (`inventory.md:49`; управление адаптерами) | 🟡 |
| §2 № 2 матрицы (таблица кодов `eam/scm/cmdb/deploymap`) | `SourceSystem.code()` — одна из шести копий; общего источника нет, теста «покрыты все коды» нет | 🔴 |
| §2 № 3 матрицы (регистр источника) | `code()` в нижнем регистре; URN `urn:corp:<code>` собирает `EventMapper`; верхний регистр — граф | 🟡 |
| Реальные источники | формат webhook (подпись, заголовки) — внешний контракт, `docs/03-interfaces.md:326-364` | 🟡 |
| Тесты, закрывающие связь | `WebhookHandlerTest`, `StubWebhookSenderTest`, `StubControlTest`; общий для SPI теста нет | 🟡 |

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| DTO — `record`'ы с проверками в компактных конструкторах | `SPI/SourceChange.java:33-42`, `SPI/ChangePage.java:20-25` | 🟢 |
| `LinkedHashMap` + `unmodifiableMap` вместо `Map.copyOf`, чтобы сохранить `null`-значения | `SPI/SourceChange.java:39-41` | 🟢 |
| Постоянное время сравнения подписи | `SPI/WebhookSignature.java:81` | 🟢 |
| Подпись над `timestamp + "." + body` включает timestamp (защита от переноса подписи на другой timestamp) | `SPI/WebhookSignature.java:48, 80` | 🟢 |
| Исключение недоступности не хранит тело ответа и `cause` (недоверенный текст, секреты) | `SPI/SourceUnavailableException.java:18-22` | 🟡 |
| Пустая страница ≠ «всё удалено»; удаление только по `snapshotComplete` | `SPI/SourceConnector.java:8-10`, `SPI/ChangePage.java:8-9` | 🟢 |

## Estado Interno

Нет: все типы неизменяемы; `WebhookSignature` — утилитный класс с приватным конструктором (`:44`), `Mac` создаётся на каждый вызов (`:90`) 🟢.

## Observabilidade

Логов, метрик и трейсов в модуле нет 🟢. Секрет не попадает в сообщения (`:93-94`).

## Riscos e Lacunas

- 🔴 Неиспользуемая зависимость на `canonical-model` (`contracts/source-spi/pom.xml:19-21`) расходится с формулировкой CLAUDE.md «contracts/* ни от чего проектного не зависят» (допустима только как внутриконтрактная; фактическая нужда не выявлена).
- 🟡 Инварианты `SourceChange` (DELETE → пустой payload; монотонность версии) только в Javadoc (`SPI/SourceChange.java:12-14`); `sourceVersion` может быть отрицательным, `sourceType/sourceId` — пустыми строками.
- 🟡 `long sourceVersion` против `SourceVersion` до 40 цифр в `event-schemas` (`contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SourceVersion.java:13`, regex `[0-9]{1,40}`): версия > `Long.MAX_VALUE` не выразима.
- 🟡 `verify` с пустым `secret` бросает `IllegalArgumentException` из `SecretKeySpec` (`SPI/WebhookSignature.java:91`; поведение JDK, не запускалось); `null` у `secret/now/window` — NPE. Смягчение: `AdapterConfig` запрещает пустой секрет (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/AdapterConfig.java:39-41`).
- 🟡 Javadoc `SourceSystem.DEPLOY_MAP` ссылается на `stub.DeployMapFormat` (`SPI/SourceSystem.java:8`); реальный класс — `io.github.unlocker.archrag.sourcestubs.DeployMapFormat` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:24`): ссылка устарела.
- 🔴 Тесты есть только на `WebhookSignature` (4 `@Test`, `SPT/WebhookSignatureTest.java:20, 27, 37, 49`); `ChangePage`, `SourceChange`, `SourceUnavailableException`, `SourceSystem` модульно не тестируются.
- 🔴 Эскиз порта в `docs/00-vision.md:485-489` (`SourcePage`, `SourceCursor`, `SourceSnapshot fetchById(SourceObjectId)`) расходится с реализованным `SourceConnector`; актуален `docs/03-interfaces.md` §5.1 (432-450).
