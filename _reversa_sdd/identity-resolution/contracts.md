# ingestion/identity-resolution: контракты

> Внешние контракты модуля: Java-порты и SQL-схема PostgreSQL. HTTP/событий модуль не выставляет. Алиасы `IR`, `MIG` — как в [`requirements.md`](requirements.md).

## 1. Порты (Java)

| Порт | Метод | Контракт | Evidence |
|------|-------|----------|----------|
| `IdentityMapping` | `Optional<UUID> find(SourceKey)` | пусто, если ключ не отображён; ничего не создаёт | `IR/IdentityMapping.java:17-18` 🟢 |
| | `UUID resolve(SourceKey)` | существующий или новый `gid`, потокобезопасно | `:20-21` 🟢 |
| | `List<SourceKey> keysOf(UUID)` | все ключи `gid` | `:23-24` 🟢 |
| | `UUID approve(Crosswalk)` | общий `gid`; `CrosswalkConflictException` при разных `gid` | `:26-33` 🟢 |
| `IdentityCandidates` | `List<IdentityCandidate> record(SourceKey, NodeLabel, Set<Feature>)` | заменяет признаки, upsert кандидатов; возвращает затронутые; `IdentityStoreException` | `IR/IdentityCandidates.java:18-25` 🟢 |
| | `void forget(SourceKey)` | удаляет признаки ключа, кандидатов не трогает | `:27-28` 🟢 |
| | `List<IdentityCandidate> candidatesOf(SourceKey)` | пары, где ключ слева или справа | `:30-31` 🟢 |
| `SourceConflicts` | `assertProperties(SourceKey, UUID gid, NodeLabel, Map<String,String>)` | заменяет утверждения, пересчитывает, возвращает открытые конфликты `gid` | `IR/SourceConflicts.java:21-28` 🟢 |
| | `assertRelation(dissent, gid, property, dissentValue, Optional<SourceKey> master, masterValue)` | см. [`source-conflicts/design.md`](source-conflicts/design.md) | `:30-48` 🟢 |
| | `retract(SourceKey, UUID gid)` | tombstone записи | `:50-54` 🟢 |
| | `openConflicts(UUID gid)` | открытые конфликты | `:56-57` 🟢 |

Реализации принимают `javax.sql.DataSource` в конструкторе; `PostgresSourceConflicts` ещё и `AuthorityMatrix` (оба `requireNonNull`) 🟢 (`IR/PostgresIdentityMapping.java:32`, `IR/PostgresIdentityCandidates.java:73-75`, `IR/PostgresSourceConflicts.java:76-79`). Сборка бинов: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:54,59,64` (матрица `AuthorityMatrix.defaults()`) 🟢.

## 2. Типы данных

| Тип | Поля и инварианты | Evidence |
|-----|------------------|----------|
| `Crosswalk(left, right, approvedBy, reason, approvedAt)` | оба ключа непусты и различны (`IllegalArgumentException("crosswalk must link two different source keys")`), `approvedBy`/`reason` не пустые (`requireText`), `approvedAt` не null | `IR/Crosswalk.java:14-25` 🟢; `CrosswalkTest` |
| `Feature(kind, value)` | `kind` не null; `value` не null и не blank (`IllegalArgumentException("feature value must not be blank")`) | `IR/Feature.java:6-13` 🟢 |
| `FeatureKind` | `HOSTNAME(0.6, strong)`, `REPOSITORY_URL(0.8, strong)`, `NAME(0.4, strong)`, `OWNER(0.2, не strong)` | `IR/FeatureKind.java:15-18` 🟢 |
| `IdentityCandidate(left, right, labelFamily, matched, score, status, firstSeenAt, updatedAt)` | `status` — `String`, перечисления нет; пара нормализована `left < right` | `IR/IdentityCandidate.java:14-22` 🟢 |
| `SourceConflict(gid, property, dissent, dissentValue, master, masterValue, status, openedAt, updatedAt, resolvedAt, kind)` | все, кроме `resolvedAt`, не null; `Status {OPEN, RESOLVED}`, `Kind {PROPERTY, RELATION}`; 10-аргументный конструктор = `Kind.PROPERTY`; `toString` без значений | `IR/SourceConflict.java:24-82` 🟢 |
| `CrosswalkConflictException extends RuntimeException` | сообщение `keys are already mapped to different gids: <a> and <b>` (`SourceKey.toString()` записи-record) | `IR/PostgresIdentityMapping.java:120-122` 🟢 |
| `IdentityStoreException extends RuntimeException(message, cause)` | сообщения: `find failed`, `resolve failed`, `keysOf failed`, `approve failed`, `record failed`, `forget failed`, `candidatesOf failed`, `assertProperties failed`, `assertRelation failed`, `retract failed`, `openConflicts failed`, `mapping vanished after insert` (cause = null) | `IR/IdentityStoreException.java:3-8` + места выше 🟢 |

Поведение `SourceConflict`: докомментарий «`resolvedAt` не `null` только у `RESOLVED`» (`IR/SourceConflict.java:12-13`) конструктором не проверяется (`:49-60`) 🟡; инвариант держит SQL.

## 3. Схема PostgreSQL (Flyway, каталог `db/migration` общий с `event-journal` и `ingestion-service`)

CHECK и FK нет ни в одной таблице; допустимость `status`/`kind` держит код 🟢.

### `identity_mapping` (`MIG/V2__identity_mapping.sql:3-12`)
`source text, source_type text, source_id text, gid uuid, created_at timestamptz` (всё NOT NULL); PK `(source, source_type, source_id)`; индекс `ix_identity_mapping_gid (gid)`. `source` = `SourceSystemCode.name()` 🟢 (`IR/PostgresIdentityMapping.java:157`).

### `approved_crosswalk` (`MIG/V2__identity_mapping.sql:15-26`)
`left_source, left_type, left_id, right_source, right_type, right_id, approved_by, reason` text, `approved_at timestamptz`, всё NOT NULL; PK по шести ключевым колонкам. Пара хранится в нормализованном порядке (`left < right` по `SourceKeys.compare`, CHECK нет: зависел бы от коллации). Повтор: `ON CONFLICT DO NOTHING` (первый `approved_by`/`reason` сохраняется) 🟢 (`IR/PostgresIdentityMapping.java:136-137`).

### `identity_feature` (`MIG/V4__identity_candidate.sql:5-16`)
`source, source_type, source_id, feature, value, label_family` text, `updated_at timestamptz`, NOT NULL; PK `(source, source_type, source_id, feature)` (один признак каждого вида на ключ); индекс `ix_identity_feature_lookup (feature, label_family, value)`. Набор ключа заменяется целиком (DELETE + INSERT) 🟢.

### `identity_candidate` (`MIG/V4__identity_candidate.sql:21-37`)
`left_*`, `right_*` (по три text), `label_family text`, `matched jsonb` (массив `{"feature","value"}` в порядке признаков), `score numeric(3,2)`, `status text DEFAULT 'OPEN'`, `first_seen_at`, `updated_at` timestamptz; PK по шести ключевым колонкам; индекс `ix_identity_candidate_right`. Читается консолью (`GRANT SELECT` в V6) 🟢.

### `property_assertion` (`MIG/V5__source_conflict.sql:6-19`)
PK `(source, source_type, source_id, property)`; `gid uuid`, `label text`, `value text` (enum -> `name`, `Instant` -> UTC ISO, строится вызывающим: `String.valueOf(value)` в `EventProcessor.java:407-408`), `authoritative boolean`, `asserted_at timestamptz`; индекс `ix_property_assertion_gid (gid, property)`. Консоль не имеет прав 🟢.

### `source_conflict` (`MIG/V5__source_conflict.sql:23-39`, `MIG/V7__source_conflict_kind.sql:4`)
PK `(gid, property, dissent_source, dissent_type, dissent_id)`; `dissent_value`, `master_value`, `master_source`, `master_type`, `master_id` NOT NULL; `status text DEFAULT 'OPEN'`; `opened_at`, `updated_at` NOT NULL, `resolved_at` NULL; `kind text NOT NULL DEFAULT 'PROPERTY'` (V7, аддитивно, PK не менялся). Для `RELATION`: `gid` — узел-источник связи, `property` — имя свойства-ссылки (`eamSystemId`, `platformId`) 🟢 (`IR/SourceConflict.java:21-22`; ADR 0030/0031).

### Нормализация признаков (формат `identity_feature.value`)

| Вид | Функция | Результат |
|-----|---------|-----------|
| `HOSTNAME` | `FeatureNormalizer.hostname` | `trim`, `toLowerCase(Locale.ROOT)`, срезаны завершающие точки; FQDN без усечения; пусто -> нет признака |
| `REPOSITORY_URL` | `repositoryUrl` | `host` в lower-case + `/` + `path` в lower-case; без схемы (`http`, `https`, `ssh`, `git`, регистр не важен), `user@`, `.git`, ведущих/завершающих `/`; scp-форма `user@host:path` разбирается; пустой host -> нет признака; путь пуст -> только host |
| `NAME` | `name` | NFKC, lower-case, серии не `\p{L}\p{N}` -> один пробел, `trim` |
| `OWNER` | `FeatureExtractor.extract` | `gid` команды `OWNED_BY`, строкой |

Evidence: `IR/FeatureNormalizer.java:15-17,21-62` 🟢; `FeatureNormalizerTest`.
