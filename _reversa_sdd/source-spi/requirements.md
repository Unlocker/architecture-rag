# contracts/source-spi — требования

> Writer, R.6a. Сокращения путей: `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`, `SPT/` = `contracts/source-spi/src/test/java/io/github/unlocker/archrag/sourcespi/`. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел.
> Use case: [`verify-webhook-signature/`](verify-webhook-signature/requirements.md), [`fetch-changes/`](fetch-changes/requirements.md), [`fetch-by-id/`](fetch-by-id/requirements.md). Внешний контракт — [`contracts.md`](contracts.md); вопросы — [`questions.md`](questions.md).

## Визуальная сводка

Модуль без состояния и без ввода-вывода: порт чтения мастер-системы (`SourceConnector`), DTO изменений и утилита HMAC-подписи webhook. Реализации порта живут в `adapters/adapter-core` (`HttpSourceConnector`), `adapters/eam-adapter` (`EamApiConnector`) и `platform/source-stubs` (`StubSource`).

## Visão Geral

SPI мастер-систем (EAM, SCM, CMDB, deploy map): единый контракт, через который адаптеры читают источник (постранично и «GET by id» после webhook), и единый формат подписи webhook-уведомлений. 🟢 (`SPI/package-info.java:1-4`, `SPI/SourceConnector.java:5-12`). Решение «мастер-системы — заглушки» — ADR 0010 (`docs/adr/0010-master-systems-are-stubs.md`).

## Responsabilidades

- Задать порт `SourceConnector`: `system()`, `fetchChanges(cursor, limit)`, `fetchById(sourceType, sourceId)` 🟢 (`SPI/SourceConnector.java:12-34`).
- Задать неизменяемые DTO: `SourceChange`, `ChangePage`, `WebhookEvent` 🟢.
- Задать словари `ChangeOperation`, `Completeness`, `SourceSystem` (коды `eam`, `scm`, `cmdb`, `deploymap`) 🟢.
- Выразить недоступность источника типизированным исключением `SourceUnavailableException`, а не пустым результатом 🟢 (`SPI/SourceConnector.java:8-10`).
- Предоставить `WebhookSignature.sign/verify` и имена трёх заголовков 🟢 (`SPI/WebhookSignature.java:25-31, 47-86`).

## Regras de Negócio

- Реализация коннектора только читает источник; побочных эффектов, кроме чтения, нет 🟢 (`SPI/SourceConnector.java:8`). Это контракт Javadoc, компилятором и тестами SPI не защищён 🟡.
- Пустой ответ означает «изменений нет» и не должен приводить к удалению данных; недоступность — только `SourceUnavailableException` 🟢 (`SPI/SourceConnector.java:8-10`).
- `fetchChanges(null, limit)` — полный snapshot с начала; иначе `cursor` = `nextCursor` предыдущей страницы 🟢 (`SPI/SourceConnector.java:20`).
- `ChangePage`: `snapshotComplete && hasMore` запрещено (`IllegalArgumentException("snapshotComplete is only valid on the last page")`); только после `snapshotComplete` разрешён missing set (ловушка 9) 🟢 (`SPI/ChangePage.java:8-9, 22-24`).
- `Completeness.PARTIAL`: отсутствующие поля нельзя трактовать как удаление (ловушка 5); в SPI флаг ни на что не влияет, смысл реализует normalizer 🟢/🟡 (`SPI/Completeness.java:3-6`).
- `SourceChange.payload` неизменяем, `null` → пустая `Map`, `null`-значения сохраняются как «поле не передано», считается недоверенным текстом источника (ловушка 11) 🟢 (`SPI/SourceChange.java:12-14, 39-41`).
- Для `DELETE` payload по контракту пуст, `sourceVersion` по контракту монотонно растёт по `(sourceType, sourceId)` — **не проверяется кодом** 🟡 (`SPI/SourceChange.java:12-14` против `:33-42`).
- Подпись webhook: `X-Archrag-Signature = "sha256=" + hex(HMAC-SHA256(secret, timestamp + "." + body))`, `X-Archrag-Timestamp` — unix-секунды, `X-Archrag-Event-Id` — идентификатор события 🟢 (`SPI/WebhookSignature.java:15-17, 47-49`).
- `verify` отвергает вне replay window в обе стороны; сравнение подписи за постоянное время; секрет не попадает в исключения и сообщения (ловушка 12) 🟢 (`SPI/WebhookSignature.java:19-20, 81, 94`).
- `SourceUnavailableException`: `statusCode` (HTTP-статус, `0` для timeout), `retryAfter` (`null`, если нет), `isRateLimited()` ⇔ `statusCode == 429`; сообщение `"<code> unavailable, status=<n>"` без тела ответа и без `cause` 🟢 (`SPI/SourceUnavailableException.java:14-36`).
- Валидация конструкторов: `null` → `NullPointerException` (`SourceChange`: `sourceType`, `sourceId`, `operation`, `completeness`, `updatedAt`; `ChangePage.changes` — через `List.copyOf`), нарушение правила → `IllegalArgumentException` (`ChangePage`) 🟢.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `WebhookSignature.sign(secret, ts, body)` возвращает `sha256=<lowercase hex>` HMAC-SHA256 над `ts + "." + body` (UTF-8) | Must | AC-01 (`verify-webhook-signature`) 🟢 |
| RF-02 | `WebhookSignature.verify` возвращает ровно один из `VALID`, `MALFORMED`, `BAD_SIGNATURE`, `OUT_OF_WINDOW` в фиксированном порядке проверок | Must | AC-01…AC-05 (`verify-webhook-signature`) 🟢 |
| RF-03 | `ChangePage` отвергает `snapshotComplete && hasMore` и копирует `changes` неизменяемо | Must | AC-03 (`fetch-changes`) 🟢 |
| RF-04 | `SourceChange` проверяет обязательные поля на `null` и замораживает `payload`, сохраняя `null`-значения | Must | AC-01 (`fetch-by-id`) 🟢 |
| RF-05 | `SourceConnector` фиксирует сигнатуры `system()`, `fetchChanges(String,int)`, `fetchById(String,String)` | Must | компиляция реализаций в `adapter-core`, `eam-adapter`, `source-stubs` 🟢 |
| RF-06 | `SourceUnavailableException` несёт `statusCode`, `retryAfter`, `isRateLimited()` | Must | AC-04 (`fetch-changes`) 🟡 (модульного теста нет) |
| RF-07 | `SourceSystem.code()` возвращает `eam`/`scm`/`cmdb`/`deploymap` | Must | AC-02 (`fetch-by-id`) 🟡 |
| RF-08 | `WebhookEvent` — запись без валидации (уведомление без полного состояния) | Should | структурно; валидацию делает `WebhookHandler` 🟢 |
| RF-09 | Инварианты `SourceChange` (DELETE → пустой payload, версия растёт) принуждаются кодом | Could | сейчас не выполняется; см. `questions.md` Q-02 🔴 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Безопасность | Сравнение подписи за постоянное время (`MessageDigest.isEqual`) | `SPI/WebhookSignature.java:81` | 🟢 |
| Безопасность | Секрет не попадает в сообщения исключений (`IllegalStateException("HmacSHA256 unavailable")`; `SourceUnavailableException` включает только код и статус) | `SPI/WebhookSignature.java:93-94`, `SPI/SourceUnavailableException.java:19` | 🟢 |
| Безопасность | Payload источника неизменяем (защита от мутации после создания) | `SPI/SourceChange.java:40-41` | 🟢 |
| Безопасность | Подпись проверяется до проверки окна; все отказы вызывающая сторона сводит к `401` без причины | `SPI/WebhookSignature.java:65-85`; `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:65-66` | 🟢 |
| Переносимость | Зависимость только от JDK (`javax.crypto`, `java.security`, `java.time`, `java.util.HexFormat`) | `SPI/WebhookSignature.java:3-10` | 🟢 |

Иных NFR (таймауты, ретраи) в модуле нет: они в `adapter-core` (`HttpSourceConnector`, `RetryPolicy`).

## Critérios de Aceitação

Подробные сценарии — в use case. Сводно:

```gherkin
Дано secret "s3cret", body {"eventId":"e1"}, timestamp = now, окно 5 минут
Когда подпись вычислена sign("s3cret", ts, body) и вызван verify с теми же значениями
Тогда результат VALID                                   # WebhookSignatureTest.validSignatureIsAccepted

Дано подпись, вычисленная другим секретом, либо изменённое тело
Когда вызван verify
Тогда результат BAD_SIGNATURE                           # WebhookSignatureTest.wrongSecretOrTamperedBodyIsRejected

Дано ChangePage(snapshotComplete=true, hasMore=true)
Когда создаётся запись
Тогда IllegalArgumentException                          # тест отсутствует 🔴 (см. questions.md Q-03)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| Порт `SourceConnector` и DTO | Must | Все 4 адаптера, заглушки и normalizer (тест) зависят от них (`dependencies.md` §3) |
| `WebhookSignature` | Must | Единственная аутентификация webhook-входа (`docs/03-interfaces.md:557`) |
| `ChangePage` инвариант | Must | Защита от удаления на неполном snapshot (ловушка 9) |
| `SourceUnavailableException` | Must | Единый сигнал retry/backoff для `Poller` и `WebhookHandler` |
| Принудительные инварианты `SourceChange` | Could | Сейчас только Javadoc |
| Класс-маркер модуля | Won't | В `source-spi` нет `*Module`-маркера, в отличие от `canonical-model`/`event-schemas` (`_reversa_sdd/code-analysis.md:16`) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `SPI/SourceConnector.java` | порт | 🟢 |
| `SPI/SourceChange.java`, `SPI/ChangePage.java`, `SPI/WebhookEvent.java` | DTO | 🟢 |
| `SPI/ChangeOperation.java`, `SPI/Completeness.java`, `SPI/SourceSystem.java` | enum | 🟢 |
| `SPI/SourceUnavailableException.java` | исключение | 🟢 |
| `SPI/WebhookSignature.java` | подпись webhook | 🟢; тест `SPT/WebhookSignatureTest.java` (4 `@Test`) |
| `SPI/package-info.java` | Javadoc пакета | 🟢 |
| `contracts/source-spi/pom.xml` | зависимость на `canonical-model` без использования (`:19-21`) | 🔴 |
