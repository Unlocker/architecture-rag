# Блок-схемы: platform/source-stubs

> Archaeologist, R.2e. Пути относительно `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/`. Разбор — `code-analysis.md` §Y1, форматы и эндпоинты — `data-dictionary.md` §Y.1. Форматы deploy map / EAM `ArchObject` — §P7.

## Журнал `StubSource`: запись и `fetchChanges(cursor, limit)` (`StubSource.java:54-70,108-168,182-202`) 🟢

```mermaid
flowchart TD
  W["upsert / upsertPartial / delete"] --> OP["UPSERT/COMPLETE, UPSERT/PARTIAL или DELETE/COMPLETE с пустым payload"]
  OP --> V["version = versions.merge('type/id', 1, sum): монотонна на ключ, растёт и на DELETE"]
  V --> E["journal.add(Entry(seq = размер+1, SourceChange(..., clock.instant()),\nnotified = !webhooksSuppressed))"]
  E --> WE["webhookEvents(): записи с notified → WebhookEvent('code-evt-seq', ...)"]
  E --> FI["fetchById: последняя запись ключа, включая DELETE"]
  E -. "журнал читает" .-> A
  A["fetchChanges(cursor, limit)"] --> L{"limit > 0?"}
  L -- нет --> IAE["IllegalArgumentException"]
  L -- да --> F["failIfRequested():\nfailuresLeft > 0 → failuresLeft--, SourceUnavailableException(status, retryAfter)"]
  F --> K{"cursor"}
  K -- "i‹seq›" --> I["инкремент: записи журнала с seq > after,\nне больше limit; last = seq последней"]
  I --> R{"reorderNextPage и страница не пуста?"}
  R -- да --> RR["флаг сброшен; страница развёрнута;\nв конец добавлен дубль первой записи"]
  R -- нет --> IP
  RR --> IP["ChangePage(page, 'i'+last,\nhasMore = журнал длиннее last,\nsnapshotComplete = false)"]
  K -- "null" --> S0["bound = размер журнала; after = 0"]
  K -- "s‹after›:‹bound›" --> S1["after, bound из курсора"]
  K -- "иной" --> IAE2["IllegalArgumentException unknown cursor"]
  S0 --> SN
  S1 --> SN["по ключу type/id оставить последнюю запись с seq ≤ bound;\nоставить только UPSERT → latest по seq"]
  SN --> PG["страница: первые limit элементов latest с seq > after"]
  PG --> M{"в latest есть seq > last?"}
  M -- да --> MC["next = 's'+last+':'+bound; hasMore = true;\nsnapshotComplete = false"]
  M -- нет --> MD["next = 'i'+bound; hasMore = false;\nsnapshotComplete = true"]
```

## HTTP-обработка `StubSourceServer.respond` (`StubSourceServer.java:79-134`) 🟢

```mermaid
flowchart TD
  R["запрос /changes или /objects/..."] --> M{"метод GET?"}
  M -- нет --> R405["405 {}"]
  M -- да --> FS["failIfScheduled(): ++calls;\nесть запланированный сбой для этого номера? → удалить и бросить"]
  FS -- сбой --> SU["SourceUnavailableException"]
  SU --> ST["статус = status; 0 → 504;\nRetry-After: секунды, если задан"]
  FS -- нет --> H{"какая ручка"}
  H -- "/changes" --> C["cursor (пусто → null), limit (по умолч. 100) → source.fetchChanges"]
  C --> OK1["200 ChangePage JSON"]
  H -- "/objects/" --> O{"ровно 2 сегмента?"}
  O -- нет --> N404["404 {}"]
  O -- да --> FB["source.fetchById(type, id), URL-декодирование"]
  FB -- есть --> OK2["200 описание изменения"]
  FB -- нет --> N404
  C -. "NumberFormatException / IAE (limit ≤ 0, неизвестный курсор)" .-> B400["400 {}"]
  C -. "AIOOBE на курсоре s… без ':' — не перехвачено" .-> DROP["обрыв соединения 🟡"]
  FB -. "failNext источника (SourceUnavailableException)" .-> SU
```

## Маршрутизация `EamApiStub.route` (`EamApiStub.java:92-156`) 🟢

```mermaid
flowchart TD
  R["GET/POST/... /api/..."] --> T{"Authorization: Token ‹токен›\n(MessageDigest.isEqual)?"}
  T -- нет --> E401["401 {detail}"]
  T -- да --> ME{"метод GET?"}
  ME -- нет --> E405["405 {detail}"]
  ME -- да --> P["сегменты пути после /api/"]
  P --> P0{"первый сегмент пуст?"}
  P0 -- да --> E404["404"]
  P0 -- нет --> TY{"сегмент == 'types'?"}
  TY -- да --> TP["types(parts, query)"]
  TP --> T1{"сегментов"}
  T1 -- "1" --> TL["200 массив typeObject по имени"]
  T1 -- "2" --> TI{"id или имя типа найдены?"}
  TI -- нет --> E404
  TI -- да --> TO["200 typeObject"]
  T1 -- "3 и options" --> TK{"kind ∈ fields, system?"}
  TK -- нет --> E400["400 {detail}"]
  TK -- да --> TOPT["200 {options:[...]} из EamApiTypeMeta"]
  TY -- нет --> HT{"store.hasType(type)?"}
  HT -- нет --> E404
  HT -- да --> NS{"сегментов после типа"}
  NS -- "0" --> LI["list: page, page_size (≤ 500), sort, fields →\nstore.page → 200 голый массив ArchObject"]
  NS -- "1" --> ID["parseId; store.find → 200 ArchObject или 404"]
  NS -- "больше" --> E404
  LI -. "IAE: page/page_size/sort" .-> E400
  ID -. "id не целое" .-> E400
```

## `EamApiStore`: upsert, архив, страница (`EamApiStore.java:28-93`) 🟢

```mermaid
flowchart TD
  U["upsert(type, id, attrs)"] --> EX{"объект есть?"}
  EX -- нет --> N["Entry(ver = 1, ctime = mtime = now, attrs = копия)"]
  EX -- да --> EQ{"attrs равны старым?"}
  EQ -- да --> NOP["ничего не менять: ver и mtime прежние"]
  EQ -- нет --> UP["Entry(ver + 1, ctime прежний, mtime = now, attrs заменены целиком)"]
  AR["archive(type, id)"] --> RM["remove из TreeMap: объект пропадает из списка и из GET id;\nповторный upsert даст ver = 1"]
  PG["page(type, sort, page, pageSize)"] --> SO{"sort задан?"}
  SO -- нет --> BYID["порядок по id"]
  SO -- да --> SK["для каждого ключа через запятую:\nid / ctime / mtime, префикс '-' = убывание;\nцепочка thenComparing + хвост по id"]
  SK -. "иной ключ" .-> IAE["IllegalArgumentException → 400"]
  SK -. "sort = ',' → chain == null" .-> NPE["NullPointerException (не перехвачено) 🟡"]
  BYID --> SL
  SK --> SL["from = min((page-1)·pageSize, размер);\nsubList(from, from + pageSize)"]
```

## `StubWebhookSender` и проверка на стороне адаптера (`StubWebhookSender.java:40-90`) 🟢

```mermaid
flowchart TD
  S["send(event)"] --> SA["sendAt(event, clock.instant())"]
  SA --> B["body(event): eventId, source, sourceType, sourceId,\nsourceVersion, operation, occurredAt — без payload"]
  B --> SG["signature = WebhookSignature.sign(secret, ts, body)\n= sha256=HMAC(ts + '.' + body)"]
  BAD["sendWithBadSignature(event)"] --> B2["то же тело; secret + '-wrong'"]
  B2 --> PO
  SG --> PO["POST target:\nContent-Type, X-Archrag-Event-Id,\nX-Archrag-Timestamp, X-Archrag-Signature"]
  OLD["sendAt(event, старый timestamp)"] --> SG
  PO --> RES{"результат"}
  RES -- "ответ" --> ST["вернуть HTTP-статус\n(202 принят; 401 OUT_OF_WINDOW / BAD_SIGNATURE на стороне адаптера)"]
  RES -- "IOException" --> ISE["IllegalStateException 'webhook delivery failed: класс'"]
  RES -- "InterruptedException" --> INT["восстановить флаг прерывания;\nIllegalStateException"]
```
