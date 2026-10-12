# Блок-схемы: contracts/source-spi

> Archaeologist, R.2a. Пути относительно `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`.

## 1. `WebhookSignature.verify` (`WebhookSignature.java:58-86`) 🟢

```mermaid
flowchart TD
  A[verify] --> B{"timestamp/signature null или нет префикса sha256=?"}
  B -- да --> M[MALFORMED]
  B -- нет --> C{"timestamp парсится в long?"}
  C -- нет --> M
  C -- да --> D{"подпись — корректный hex?"}
  D -- нет --> M
  D -- да --> E["expected = HMAC-SHA256(secret, ts + '.' + body)"]
  E --> F{"MessageDigest.isEqual(expected, provided)?"}
  F -- нет --> BS[BAD_SIGNATURE]
  F -- да --> G{"|now - ts| > window?"}
  G -- да --> OW[OUT_OF_WINDOW]
  G -- нет --> V[VALID]
```

Вызывающая сторона (`WebhookHandler`, R.2b) сводит три отказа к одному `401` без тела (`docs/03-interfaces.md`, «Подпись, timestamp, replay window») 🟢.

## 2. Сценарий webhook → follow-up (`SourceConnector.fetchById`, `WebhookEvent.java:6-7`) 🟡

```mermaid
sequenceDiagram
  participant SRC as Мастер-система
  participant WH as Адаптер (WebhookServer)
  participant C as SourceConnector
  participant J as EventJournal
  SRC->>WH: POST /webhook (WebhookEvent + HMAC)
  WH->>WH: WebhookSignature.verify
  WH->>C: fetchById(sourceType, sourceId)
  alt объект найден
    C-->>WH: SourceChange (UPSERT или DELETE)
  else неизвестен
    C-->>WH: Optional.empty
  end
  WH->>J: append (через InboxWriter)
  WH-->>SRC: 202
```

Верхняя часть — контракт SPI 🟢; шаги `InboxWriter`/`202` взяты из `docs/03-interfaces.md` и проверяются в R.2b 🟡.

## 3. Постраничное чтение и полнота snapshot (`SourceConnector.fetchChanges`, `ChangePage.java:17-26`) 🟢

```mermaid
flowchart TD
  S[cursor = null: полный snapshot] --> F["fetchChanges(cursor, limit)"]
  F -- SourceUnavailableException --> RB[повтор с backoff, retryAfter если задан]
  F --> P[ChangePage]
  P --> V{"snapshotComplete && hasMore?"}
  V -- да --> X[IAE при создании страницы]
  V -- нет --> H{hasMore?}
  H -- да --> N["cursor = nextCursor"] --> F
  H -- нет --> C{snapshotComplete?}
  C -- да --> R["можно считать missing set (маркер snapshot-complete)"]
  C -- нет --> I["инкрементальный polling: cursor = nextCursor"]
```

Пустая страница означает «изменений нет», а не «всё удалено» (`SourceConnector.java:8-10`); обработка missing set — только после `snapshotComplete` (ловушка 9) 🟢.
