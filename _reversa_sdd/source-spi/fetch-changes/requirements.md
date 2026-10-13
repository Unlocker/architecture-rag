# fetch-changes — требования

> Модуль: [`../requirements.md`](../requirements.md). `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`, `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## Visão Geral
Постраничное чтение изменений мастер-системы `SourceConnector.fetchChanges(cursor, limit)` → `ChangePage`: полный snapshot (`cursor == null`) и инкрементальный polling; сигнал недоступности через `SourceUnavailableException` 🟢 (`SPI/SourceConnector.java:17-24`).

## Regras de Negócio
- `cursor == null` — полный snapshot с начала; иначе `nextCursor` предыдущей страницы (непрозрачный) 🟢.
- `limit` — максимум изменений на странице, положительное число; SPI не проверяет, реализация `HttpSourceConnector` бросает IAE `limit must be positive` при `limit <= 0` 🟢 (`AC/HttpSourceConnector.java:52-54`).
- `ChangePage`: `snapshotComplete && hasMore` → IAE; только после `snapshotComplete` разрешён missing set 🟢 (`SPI/ChangePage.java:22-24`).
- Пустой `changes` = «изменений нет», не «всё удалено» 🟢 (`SPI/SourceConnector.java:8-10`).
- Недоступность (429/5xx/timeout) — `SourceUnavailableException(system, statusCode, retryAfter)`, `statusCode=0` для timeout, `retryAfter` может быть `null`; `isRateLimited()` ⇔ 429 🟢 (`SPI/SourceUnavailableException.java:14-36`). Реализации: 429/≥500 → исключение со статусом, `IOException` → статус 0 (`AC/HttpSourceConnector.java:90-100`) 🟢.
- Потребитель (`Poller`): snapshot без `snapshotComplete=true` на последней странице → `IllegalStateException("<code> ended snapshot without snapshot-complete")`, маркер `snapshot-complete` не пишется; `hasMore` без нового курсора → `IllegalStateException("<code> reported hasMore without advancing the cursor")` 🟢 (`AC/Poller.java:130-132, 164-167, 176-178`) — вне SPI, дано для контекста.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-01 | `ChangePage` копирует `changes` неизменяемо | Must | AC-02 |
| RF-02 | `ChangePage` запрещает `snapshotComplete && hasMore` | Must | AC-03 |
| RF-03 | `SourceUnavailableException` несёт статус, `retryAfter`, `isRateLimited()` | Must | AC-04 |
| RF-04 | Реализации сигнализируют недоступность только этим исключением | Must | AC-05 (проверяется в `adapter-core`) |

## Requisitos Não Funcionais
Повторы с backoff (`maxAttempts=5`, `baseDelay=500 мс`, `maxDelay=30 с`) задаёт `RetryPolicy.defaults()` (`AC/RetryPolicy.java:31-33`) — вне SPI; `retryAfter` источника ограничивается `maxDelay` (`AC/RetryPolicy.java:45-49`) 🟢.

## Critérios de Aceitação

```gherkin
AC-01
Дано ChangePage(changes=[c1], nextCursor="n1", hasMore=true, snapshotComplete=false)
Когда создаётся запись
Тогда запись создана; changes неизменяем                       # тест отсутствует 🔴

AC-02
Дано список changes, изменяемый после создания страницы
Когда в исходный список добавляют элемент
Тогда page.changes() не меняется; null вместо списка → NullPointerException   # тест отсутствует 🔴

AC-03 (негативный)
Дано snapshotComplete=true и hasMore=true
Когда создаётся ChangePage
Тогда IllegalArgumentException "snapshotComplete is only valid on the last page"   # тест SPI отсутствует 🔴

AC-04
Дано SourceUnavailableException(SourceSystem.EAM, 429, Duration.ofSeconds(7))
Когда читаются поля
Тогда isRateLimited()==true, retryAfter()==7 с, message=="eam unavailable, status=429"   # тест SPI отсутствует 🔴; потребитель: RetryPolicyTest (AC/RetryPolicyTest.java:18), PollerTest (:342)

AC-05 (негативный, потребитель)
Дано источник вернул последнюю страницу snapshot с snapshotComplete=false
Когда Poller завершает полный проход
Тогда IllegalStateException, маркер snapshot-complete не записывается   # PollerTest.incompleteSnapshotDoesNotWriteMarkerOrMoveCursor (adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/PollerTest.java:63) 🟢
```

## Prioridade (MoSCoW)
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-02 | Must | защита от удаления на неполном snapshot |
| RF-01, RF-03, RF-04 | Must | используются `Poller`/`Reconciler`-цепочкой |

## Rastreabilidade de Código
| Файл | Класс | Покрытие |
|---|---|---|
| `SPI/ChangePage.java` | `ChangePage` | 🟢 |
| `SPI/SourceConnector.java` | `fetchChanges` | 🟢 |
| `SPI/SourceUnavailableException.java` | исключение | 🟢 |
