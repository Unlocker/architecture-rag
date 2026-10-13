# ingestion/identity-resolution: дизайн

> Алиасы: `IR`, `MIG`, `IT` — как в [`requirements.md`](requirements.md); `EP` = `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java`. Блок-схемы: [`../../flowcharts/identity-resolution.md`](../flowcharts/identity-resolution.md). Схема SQL и сигнатуры портов: [`contracts.md`](contracts.md).

## Интерфейс

Три порта + три `Postgres*`-реализации + чистые функции (см. `contracts.md` §1-2). Реализации `final`, без состояния, кроме `DataSource` (и `AuthorityMatrix`); потокобезопасны сами по себе, вся синхронизация — в PostgreSQL 🟢.

Общий каркас JDBC-метода 🟢: `try (Connection c = dataSource.getConnection()) { c.setAutoCommit(false); try { ...; c.commit(); } catch (SQLException | RuntimeException e) { c.rollback(); throw e; } } catch (SQLException e) { throw new IdentityStoreException("<op> failed", e); }`. Следствие: `RuntimeException` (в т. ч. `CrosswalkConflictException`) пробрасывается как есть, `SQLException` оборачивается (`IR/PostgresIdentityMapping.java:45-60`, `IR/PostgresSourceConflicts.java:146-160`). Чтения без записи (`find`, `keysOf`, `candidatesOf`, `openConflicts`) идут без явной транзакции, `autoCommit` по умолчанию 🟢.

## Поток интеграции (кто и когда вызывает)

Модуль пассивный: все вызовы идут из `ingestion/*`. Порядок внутри `EventProcessor` для upsert-события 🟢 (`EP:227-248`, затем запись графа):

1. `resolveGids` -> `identity.resolve(key)` для ключа события, для концов связей только `identity.find` (чужой конец `gid` не создаёт) (`EP:588` и далее).
2. `recordCandidates` -> `FeatureExtractor.labelOf` + `FeatureExtractor.extract` -> `IdentityCandidates.record` (`EP:232,394-396`); `IdentityStoreException` -> `RETRYING`, код `IDENTITY_CANDIDATE_FAILED` (`EP:233-235`).
3. `assertProperties` (`SourceConflicts.assertProperties`, `EP:403-411`), затем `assertSystemRelation`, `assertSystemPlatform` (`assertRelation`); `IdentityStoreException` -> `RETRYING`, код `SOURCE_CONFLICT_FAILED` (`EP:246-248`).
4. Запись в Neo4j (проектор) с маркерами конфликтов (`ASSERTS.conflicts`).

Для tombstone: `candidates.forget(key)` (`EP:271-273`) и `identity.find(key)` -> `conflicts.retract(key, gid)` (`EP:565`; также `Reconciler.java:153`). Crosswalk: `CrosswalkService.loadOne` -> `identity.approve` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:49`) 🟢.

Все вызовы идут вне транзакции Neo4j (ловушка 7 CLAUDE.md): комментарии «вне транзакции Neo4j» в `EP:391,401`; повтор события безопасен, т. к. операции идемпотентны 🟢.

## Компоненты и ключевые алгоритмы

| Компонент | Что делает | Evidence |
|-----------|-----------|----------|
| `SourceKeys.compare` | порядок пар: `source().name()`, `sourceType`, `sourceId`, `String.compareTo` | `IR/SourceKeys.java:10-16` 🟢 |
| `LabelFamily.of` | `label.isA(COMPUTE_INSTANCE) ? COMPUTE_INSTANCE : label` | `IR/LabelFamily.java:10-12` 🟢 |
| `FeatureExtractor` | `switch (data)`: `ComputeInstance` -> `HOSTNAME(hostname)`, `Repository` -> `REPOSITORY_URL(url)`, `ITSystem`/`Service`/`Team` -> `NAME(name)`; остальные метки (`Environment`, `Deployment`, ...) без признаков; для `ITSystem`/`Service` ещё `OWNER` = `gid` конца **первой** команды `UpsertRelation` типа `OWNED_BY` с `from == key`, если `gids.get(to) != null`; нет `UpsertNode` для ключа -> пусто | `IR/FeatureExtractor.java:43-69`; тесты `FeatureExtractorTest` 🟢 |
| `FeatureKind.score` | `EnumSet` различных видов, сумма весов, `min(1)`, `setScale(2, HALF_UP)` | `IR/FeatureKind.java:38-47` 🟢 |

### Mapping и crosswalk (use case [`gid-mapping-crosswalk`](gid-mapping-crosswalk/design.md))
`resolve`: `getOrCreate(c, key, randomUUID())` = `INSERT ... ON CONFLICT DO NOTHING` + `SELECT`. `approve`: упорядочить пару, `link` (прочитать `gid` обоих, конфликт при разных, `gid = ga ?? gb ?? random`, `getOrCreate` для обоих, повторная проверка равенства), `INSERT approved_crosswalk ON CONFLICT DO NOTHING`, `commit` 🟢.

### Признаки и кандидаты (use case [`candidate-matching`](candidate-matching/design.md))
Два уровня advisory-lock'ов -> замена признаков -> поиск пар по сильным признакам -> добор слабого `OWNER` -> upsert пар 🟢.

### Конфликты (use case [`source-conflicts`](source-conflicts/design.md))
Lock `conflict|<gid>` -> замена утверждений с флагом `authoritative = matrix.isAuthoritative(label, property, key.source())` -> `recompute`: расходящиеся пары -> upsert `OPEN`; открытые `PROPERTY` вне списка -> `RESOLVED` 🟢.

## Зависит от / Связанные спецификации

Из `_reversa_sdd/traceability/spec-impact-matrix.md` (§1 строка `ingestion/identity-resolution`, §2 связи вне pom).

| Связь | Что значит для модуля | Источник |
|-------|-----------------------|----------|
| `contracts/canonical-model` (прямая, pom) | `NodeLabel`, `SourceKey`, `SourceSystemCode`, `AuthorityMatrix`, `GraphCommand`/`UpsertNode`/`UpsertRelation`, `NodeData`-подтипы, `RelationType.OWNED_BY`, `Invariants.requireText`. Новая метка/тип связи/источник требует пересмотра `FeatureExtractor` (switch с `default -> {}`: новая метка молча без признаков), `LabelFamily`, `SourceSystemCode.valueOf` в чтении БД | матрица §1 строка 1; §3 «Новая метка», «Новый источник» 🟢 |
| Влияет прямо на `ingestion/graph-projector` и `ingestion-service` | порты и семантика `IdentityStoreException`, `CrosswalkConflictException`; `ingestion-service` строит бины и вызывает `approve` | матрица §1 строка 8 🟢 |
| Через данные: Flyway `V2/V4/V5/V7` | миграции модуля в **общем каталоге** `db/migration` с `V1` (`event-journal`) и `V3/V6` (`ingestion-service`); сквозная нумерация, дубль версии ломает старт; в одиночку модуль миграции не применяет | матрица §2 № 14 🟡 |
| Через данные: консоль | `identity_candidate`, `source_conflict` читает `archrag_console_ro` (`GRANT SELECT` в `V6__console_readonly_role.sql:15`); новая таблица/колонка в запросе консоли без `GRANT` даёт `permission denied`; `identity_mapping`, `identity_feature`, `property_assertion` консоли недоступны | матрица §2 № 5 🟡 |
| Через данные: UI-вкладка Identity | фильтр статусов кандидата `OPEN/RESOLVED` в UI при фактическом `OPEN` навсегда (🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)] Q-01) | матрица §1; `_reversa_sdd/code-analysis.md` (admin-ui, Identity) |
| Два нормализатора URL репозитория | `FeatureNormalizer.repositoryUrl` (всё в lower-case, `host/path`) против `GitUrls.normalize` в `ScmMapper` (регистр пути сохраняется); TODO в `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/ScmMapper.java:103-104`. Хранимый `url` на кандидатов не влияет | матрица §2 № 11 🟡 |
| Три `AuthorityMatrix.defaults()` | `PostgresSourceConflicts` получает матрицу из `IngestionServiceConfiguration.java:64`, проектор — со строки 86, `ExplainProvenanceTool` — своя. Таблица одна (`canonical-model`), но расхождение экземпляров возможно при появлении конфигурации | матрица §2 № 12 🟢 |
| Код источника в `SourceKey.source().name()` | колонки `source` хранят `SourceSystemCode.name()` (`EAM`, `SCM`, `CMDB`, `DEPLOYMAP`, `MANUAL`); регистр и таблица URN отдельно (`EventMapper`, `Normalizer`, `EP`) | матрица §2 № 2, № 3 🟡 |
| Типичные последствия | «Новый источник данных» затрагивает `identity-resolution` (AuthorityMatrix, признаки); «Новая таблица PG» — миграция + GRANT V6 | матрица §3 🟢 |

ADR: [0019](../../docs/adr/0019-identity-resolution-no-auto-merge.md) (без auto-merge, три ступени), [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) (tombstone, `source_conflict` не удаляется по духу), [0030](../../docs/adr/0030-deployment-system-conflict.md) и [0031](../../docs/adr/0031-it-system-platform-invariant.md) (`RELATION`), [0003](../../docs/adr/0003-gid-and-source-record-identity.md) (gid) — только как ссылки; карта `_reversa_sdd/adrs/retroactive-map.md:28,46`.

- Спецификации потребителей: проекция и tombstone/`retract` — [`graph-projector`](../graph-projector/design.md) (use case [`process-event`](../graph-projector/process-event/design.md)); сборка бинов, `CrosswalkService`, загрузка crosswalk — [`ingestion-service`](../ingestion-service/design.md) (use case [`load-crosswalk`](../ingestion-service/load-crosswalk/design.md)). Открытый 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)] «`approve` не обновляет граф до replay» (Q-03) разобран в [`graph-projector/questions.md`](../graph-projector/questions.md) (Q-02).

## Решения проекта

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| PostgreSQL, а не граф, хранит mapping: rebuild графа даёт те же `gid` | `MIG/V2__identity_mapping.sql:1-2`; ADR 0019 | 🟢 |
| JDBC без ORM, SQL строковыми константами | `IR/PostgresIdentityMapping.java:17-28` | 🟢 |
| Порядок пары задаёт приложение (не CHECK в БД) | `MIG/V2__identity_mapping.sql:14`; `IR/SourceKeys.java:5` | 🟢 |
| Advisory-lock вместо `SERIALIZABLE`/`SELECT FOR UPDATE`: при READ COMMITTED параллельные `record` не видят друг друга | `IR/PostgresIdentityCandidates.java:23-26,84-85,155-161` | 🟢 |
| Веса и `MAX_MATCHES` — константы кода | `IR/FeatureKind.java:11`, `IR/PostgresIdentityCandidates.java:33` | 🟢 |
| Допущение PoC: пропуск популярного признака перезаписывает `matched` без него | `IR/PostgresIdentityCandidates.java:26-28,212-216` | 🟢 |
| Сравнение конфликтов идёт по утверждениям всех записей `gid`, а не по графу | `IR/SourceConflicts.java:13-15` | 🟢 |
| Единственный мастер на свойство в PoC; при нескольких — детерминированно по ключу записи | `IR/PostgresSourceConflicts.java:41-42` | 🟢 |
| `status`/`kind` без CHECK и перечисления для кандидата | `MIG/V4__identity_candidate.sql:31` | 🟢 |

## Состояние

Все данные — в таблицах PG (см. `contracts.md` §3). Таблицы изменяются только заменой набора записи (`DELETE`+`INSERT` в `identity_feature` и `property_assertion`) и upsert; физических `DELETE` в `identity_mapping`, `approved_crosswalk`, `identity_candidate`, `source_conflict` нет 🟢 (grep `DELETE` по модулю: `PostgresIdentityCandidates.java:38-39`, `PostgresSourceConflicts.java:36-37`). Машина состояний `source_conflict`: `OPEN <-> RESOLVED` ([`source-conflicts/design.md`](source-conflicts/design.md)); `identity_candidate.status` — только `OPEN` 🟢/🔴.

## Наблюдаемость

Единственный лог модуля: `System.Logger` `WARNING` «identity candidates skipped: feature {0} of {1} matches more than {2} records» (вид, семейство, предел) 🟢 (`IR/PostgresIdentityCandidates.java:212-214`). Метрик и трейсов нет. Ошибки видны вызывающему как `IdentityStoreException` и переводят событие в `RETRYING` с кодами выше 🟢.

## Риски и 🔴

- 🔴 Жизненный цикл `identity_candidate.status` не реализован; crosswalk не закрывает кандидата (`IR/IdentityCandidate.java:20`; `MIG/V4:31`).
- 🟡 Crosswalk меняет `identity_mapping`, но не `property_assertion.gid`, `identity_feature`, `identity_candidate` и не перепроецирует граф: `property_assertion.gid` остаётся прежним до повторного `assertProperties` записи; граф исправится при следующем replay/rebuild (`IR/PostgresIdentityMapping.java:80-101` не обращается к другим таблицам; `_reversa_sdd/detective-gaps.md:41` П-12).
- 🟡 Флаг `authoritative` в `property_assertion` вычислен в момент записи по матрице; смена матрицы пересчитается только при следующей записи (`IR/PostgresSourceConflicts.java:96`).
- 🟡 Пропуск признака при >50 совпадениях не удаляет ранее созданных кандидатов по нему (`IR/PostgresIdentityCandidates.java:212-216`: `continue`, upsert затрагивает только найденные пары).
- 🟡 Коллизия 64-битного хэша `hashtextextended` даёт лишнюю сериализацию, не потерю корректности (`IR/PostgresIdentityCandidates.java:37,164-169`).
- 🟡 `Set<Feature>` с двумя признаками одного вида нарушит PK `identity_feature` -> `IdentityStoreException("record failed")`; `FeatureExtractor` такого не создаёт (по одному на вид) (`IR/FeatureExtractor.java:51-55`, `MIG/V4:13`).
- 🟡 `SourceConflict` не проверяет «`resolvedAt` не null только у `RESOLVED`» (`IR/SourceConflict.java:12-13` против `:49-60`).
