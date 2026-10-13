# fetch-by-id — требования

> Модуль: [`../requirements.md`](../requirements.md). `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`, `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## Visão Geral
После валидного webhook (`WebhookEvent` — уведомление без состояния) адаптер читает актуальное состояние объекта через `SourceConnector.fetchById(sourceType, sourceId)` и получает `SourceChange` 🟢 (`SPI/WebhookEvent.java:5-7`, `SPI/SourceConnector.java:26-33`).

## Regras de Negócio
- `fetchById` возвращает `Optional<SourceChange>`: пусто, если источник объект не знает 🟢.
- Удалённый объект, который источник ещё помнит, возвращается как изменение с `ChangeOperation.DELETE` 🟢 (`SPI/SourceConnector.java:27-28`).
- `SourceChange`: `NPE` на `null` у `sourceType`, `sourceId`, `operation`, `completeness`, `updatedAt`; `payload == null` → пустая неизменяемая карта; `null`-значения сохраняются 🟢 (`SPI/SourceChange.java:33-42`).
- Не проверяются: знак `sourceVersion`, пустота `sourceType/sourceId`, пустой payload у `DELETE` 🟡.
- Недоступность → `SourceUnavailableException`; `WebhookHandler` отвечает `503`, без повторов в webhook-пути 🟢 (`AC/WebhookHandler.java:85-89`).
- Пусто + операция уведомления `DELETE` + `sourceVersion >= 0` → потребитель строит tombstone `SourceChange(DELETE, COMPLETE, clock.instant(), Map.of())`; пусто и не `DELETE` → `404` 🟢 (`AC/WebhookHandler.java:91-103`) — вне SPI.
- Payload — недоверенный текст источника, не интерпретируется и не логируется целиком (ловушка 11) 🟢 (`SPI/SourceChange.java:12-14`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-01 | `SourceChange` обязателен по 5 полям, иначе NPE | Must | AC-02 |
| RF-02 | `payload` неизменяем, сохраняет `null`-значения | Must | AC-01 |
| RF-03 | `fetchById` отличает «не знаю» (`Optional.empty`) от «недоступно» (исключение) | Must | AC-03 |
| RF-04 | `SourceSystem.code()` — стабильные коды | Must | AC-04 |

## Requisitos Não Funcionais
Таймауты GET-запросов: connect 5 с (`AC/HttpSourceConnector.java:37`), запрос 10 с по `docs/03-interfaces.md:351` — вне SPI 🟡.

## Critérios de Aceitação

```gherkin
AC-01
Дано payload {"name":"A","owner":null}
Когда создаётся SourceChange
Тогда payload содержит ключ owner со значением null и не изменяем (put -> UnsupportedOperationException)   # тест отсутствует 🔴

AC-02 (негативный)
Дано sourceId = null (или operation/completeness/updatedAt/sourceType = null)
Когда создаётся SourceChange
Тогда NullPointerException с именем поля   # тест отсутствует 🔴

AC-03
Дано webhook подписан верно, а источник на fetchById вернул 404
Когда нет DELETE в уведомлении
Тогда fetchById возвращает Optional.empty, WebhookHandler отвечает 404   # WebhookHandlerTest.unknownObjectAnswersNotFound (adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/WebhookHandlerTest.java:153) 🟢

AC-04
Дано SourceSystem.DEPLOY_MAP
Когда вызывается code()
Тогда "deploymap"   # тест отсутствует 🔴
```

## Prioridade (MoSCoW)
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-03 | Must | основной путь webhook → inbox |
| Принуждение инвариантов DELETE | Could | см. Q-02 |

## Rastreabilidade de Código
| Файл | Класс | Покрытие |
|---|---|---|
| `SPI/SourceChange.java` | `SourceChange` | 🟢 |
| `SPI/WebhookEvent.java` | `WebhookEvent` | 🟢 |
| `SPI/SourceConnector.java` | `fetchById` | 🟢 |
| `SPI/ChangeOperation.java`, `SPI/Completeness.java`, `SPI/SourceSystem.java` | enum | 🟢 |
