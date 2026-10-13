# Use case: raw-payload-store (raw payload в S3, адресация содержимым)

> Модульные требования и сокращения (`S3S`, `PEJ`): [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Обзор

`S3RawPayloadStore` реализует порт `RawPayloadStore`: сохраняет байты raw payload в S3-совместимом бакете под ключом `raw/<source>/<sha256>` и читает их обратно с проверкой hash. Адаптеры вызывают `put` до `append`; `ingestion` вызывает `get` при replay/rebuild и `put` для tombstone `Reconciler`. В `inbox_event` хранится только ссылка (`payload_key`, `payload_hash`).

## Бизнес-правила

1. `put`: `source` должен соответствовать `[A-Za-z0-9:._-]{1,200}` и не состоять только из точек; иначе `IllegalArgumentException("source contains characters not allowed in an object key")` до любого сетевого вызова 🟢 (`S3S:33`, `S3S:68-72`).
2. Hash — SHA-256 содержимого в hex нижнего регистра (64 символа); ключ `"raw/" + source + "/" + hash` 🟢 (`S3S:73-74`, `S3S:101-108`).
3. Запись идемпотентна: `HEAD` по ключу; `PutObject` только при `NoSuchKeyException` 🟢 (`S3S:75-77`, `S3S:91-99`).
4. Возврат — `RawPayloadRef(key, hash)` (оба поля обязательны, `RawPayloadRef` бросает при пустых) 🟢 (`S3S:78`, `ES/RawPayloadRef.java:5-9`).
5. `get(ref)`: `GetObject` по `ref.key()`, пересчёт SHA-256 и сравнение с `ref.contentHash()`; расхождение → `IllegalStateException("raw payload hash mismatch for key <key>")` 🟢 (`S3S:81-89`).
6. `NoSuchKeyException` из `get` не перехватывается и уходит вызывающему 🟢 (`S3S:82-84`).
7. `ensureBucket`: `HEAD` бакета; `NoSuchBucketException` → `CreateBucket`; вызывается при старте приложения 🟢 (`S3S:59-66`; `IngestionServiceConfiguration.java:80`, `DemoLauncher.java:119`).
8. `close()` закрывает `S3Client`; конструктор `(S3Client, bucket)` передаёт владение хранилищу 🟢 (`S3S:54-57`, `S3S:110-112`).
9. Содержимое payload — недоверенные данные источника: модуль его не разбирает и не логирует 🟢 (логов нет).

## Требования

| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-S1 | Ключ `raw/<source>/<sha256>` | Must | AC-S1 |
| RF-S2 | Валидация `source` до обращения к S3 | Must | AC-S2 |
| RF-S3 | Идемпотентный `put` (без повторной записи) | Must | AC-S1 |
| RF-S4 | `get` проверяет hash | Must | AC-S3 |
| RF-S5 | `ensureBucket` создаёт бакет при отсутствии | Should | AC-S4 |
| RF-S6 | Клиент создаётся с path-style, `us-east-1`, `WHEN_REQUIRED` checksum-ами | Must | AC-S5 |

## Нефункциональные

| Тип | Требование | Evidence | Метка |
|---|---|---|---|
| Безопасность | Ключ объекта не может выйти за префикс `raw/<source>/`: запрещены `/`, пробелы, только-точки | `S3S:33`, `S3S:70` | 🟢 |
| Безопасность | Ключи доступа приходят аргументами, не логируются, не попадают в сообщения исключений | `S3S:39-51` | 🟢 |
| Целостность | SHA-256 при чтении | `S3S:85-87` | 🟢 |
| Совместимость | Дополнительные checksum-ы SDK отключены (`WHEN_REQUIRED`) ради не-AWS S3 | `S3S:44-46` | 🟢 |
| Эксплуатация | Нет Netty/Apache-клиентов; `UrlConnectionHttpClient` | `S3S:48`, `ingestion/event-journal/pom.xml:38-51` | 🟢 |

## Критерии приёмки

```gherkin
# AC-S1 (позитив: адресация и идемпотентность)
Дано пустой бакет и тело "{\"a\":1}"
Когда put("urn:corp:eam", body) вызывается дважды
Тогда оба вызова возвращают равные RawPayloadRef
И ref.key() == "raw/urn:corp:eam/" + ref.contentHash()
И get(ref) возвращает те же байты
# trace: EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed (SeaweedFS)

# AC-S2 (негатив: небезопасный source)
Дано source из набора "x/../../y", "a/b", "..", "", "a b"
Когда put(source, new byte[0])
Тогда IllegalArgumentException (клиент S3 не вызывается)
# trace: S3RawPayloadStoreTest.rejectsSourceThatCouldEscapeKeyPrefix

# AC-S3 (негатив: подмена содержимого)
Дано объект по ключу ref.key()
Когда get(new RawPayloadRef(ref.key(), "0".repeat(64)))
Тогда IllegalStateException
# trace: EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed

# AC-S4 (ensureBucket)
Дано бакета нет
Когда ensureBucket() (и повторно)
Тогда бакет создан; повторный вызов не создаёт и не падает
# trace: EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed вызывает ensureBucket один раз; повтор не проверен 🟡

# AC-S5 (стабильность hash)
Когда sha256("abc")
Тогда "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
# trace: S3RawPayloadStoreTest.sha256IsStableHex
```

## MoSCoW

Must: RF-S1..S4, S6. Should: RF-S5. Won't: версионирование, lifecycle/retention, метаданные объекта, повторное чтение/проверка существующего объекта в `put`.

## Зависит от / связанные спецификации

`contracts/event-schemas` (`RawPayloadStore`, `RawPayloadRef`); потребители: `adapter-core` (put), `ingestion-service` (get, `StoredEventReader`, `ReplayService`), `graph-projector` (`Reconciler` пишет raw tombstone); формат содержимого — вне модуля (`spec-impact-matrix.md` №6, №7); ADR [0007](../../../docs/adr/0007-s3-raw-storage-seaweedfs.md); [`../design.md`](../design.md), [`../contracts.md`](../contracts.md) §2.

## Прослеживаемость

`S3S:30-113`; `S3RawPayloadStoreTest` (2 метода); `EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed`.
