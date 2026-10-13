# raw-payload-store: дизайн

> Общий дизайн: [`../design.md`](../design.md). Сокращения: `S3S`.

## Интерфейс

| Символ | Сигнатура | Evidence |
|---|---|---|
| `S3RawPayloadStore.create` | `static (URI endpoint, String accessKey, String secretKey, String bucket)` | `S3S:39-51` |
| конструктор | `(S3Client s3, String bucket)` | `S3S:54-57` |
| `ensureBucket` | `void ensureBucket()` | `S3S:59-66` |
| `put` | `RawPayloadRef put(String source, byte[] content)` | `S3S:68-79` |
| `get` | `byte[] get(RawPayloadRef ref)` | `S3S:81-89` |
| `sha256` | `static String sha256(byte[])` (package-private; используется также `PostgresEventJournal.fingerprint`) | `S3S:101-108` |
| `close` | `void close()` — закрывает `S3Client` | `S3S:110-112` |

## Сборка клиента (`create`) 🟢 (`S3S:40-51`)

`S3Client.builder()`: `endpointOverride(endpoint)`, `region(US_EAST_1)`, `forcePathStyle(true)`, `requestChecksumCalculation(WHEN_REQUIRED)`, `responseChecksumValidation(WHEN_REQUIRED)`, `credentialsProvider(StaticCredentialsProvider(AwsBasicCredentials(accessKey, secretKey)))`, `httpClient(UrlConnectionHttpClient.create())`.

## Основной поток `put`

1. Проверка `source`: `SOURCE_PATTERN.matcher(source).matches()` и не все символы `.` (`S3S:69-72`).
2. `hash = sha256(content)` (`S3S:73`).
3. `key = "raw/" + source + "/" + hash` (`S3S:74`).
4. `exists(key)`: `headObject`; `NoSuchKeyException` → `false` (`S3S:75`, `S3S:91-99`).
5. Не существует → `putObject(bucket, key, RequestBody.fromBytes(content))` (`S3S:75-77`).
6. Вернуть `new RawPayloadRef(key, hash)` (`S3S:78`).

## Основной поток `get`

1. `getObjectAsBytes(bucket, ref.key()).asByteArray()` (`S3S:82-84`).
2. `sha256(content)` равен `ref.contentHash()`? нет → `IllegalStateException("raw payload hash mismatch for key " + ref.key())` (`S3S:85-87`).
3. Вернуть байты.

`sha256` использует `MessageDigest.getInstance("SHA-256")` и `HexFormat.of().formatHex` (нижний регистр); недоступность алгоритма → `IllegalStateException("SHA-256 unavailable", e)` (`S3S:101-108`).

## Альтернативные потоки

- Бакет отсутствует при `put/get` → исключение AWS SDK, не перехватывается 🟢.
- Гонка двух `put` одного содержимого: оба могут выполнить `PUT` одних и тех же байт — безвредно 🟡.
- Существующий объект с повреждённым содержимым не перезаписывается (проверяется только наличие ключа); ошибку выявит `get` 🟡 (`S3S:75`).
- `ensureBucket`: `HEAD` → `NoSuchBucketException` → `CreateBucket`; другие ошибки пробрасываются (`S3S:59-66`) 🟢.

## Зависимости

AWS SDK v2 `s3` (без `netty-nio-client`, `apache-client`), `url-connection-client`; JDK `MessageDigest`, `HexFormat`. Конфигурация (значения секретов не приводятся): `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_S3_BUCKET` (`ingestion/ingestion-service/src/main/resources/application.yml:27-31`).

## Зависит от / связанные спецификации

[`../design.md`](../design.md), [`../contracts.md`](../contracts.md) §2; ADR 0007 (SeaweedFS вместо MinIO в тестах/на стенде); `ingestion-service` (`IngestionServiceConfiguration.java:77-80`), `demo-launcher` (`DemoLauncher.java:117-119`); `_reversa_sdd/database/data-dictionary.md` (раздел S3).

## Решения

| Решение | Evidence | Метка |
|---|---|---|
| Ключ несёт SHA-256 → объект неизменяем, повторная запись не нужна | `S3S:27-30`, `S3S:74-77` | 🟢 |
| Целостность проверяет приложение, а не дополнительные checksum-ы SDK | `S3S:44-46` | 🟢 |
| Валидация `source` по allowlist-регулярке, а не экранированием | `S3S:33`, `S3S:70` | 🟢 |
| `JournalException` для S3 не используется: ошибки SDK пробрасываются | `S3S:59-99` | 🟢 |

## Риски

- 🟡 Retention и lifecycle для `raw/*` не заданы (PoC, П-20).
- 🟡 `ensureBucket` ловит только `NoSuchBucketException`; поведение иных S3-совместимых хранилищ, отдающих другой код на отсутствующий бакет, проверялось лишь на SeaweedFS в `EventJournalIT`.
- 🟡 `put` не проверяет размер содержимого; весь payload держится в памяти (`byte[]`).
