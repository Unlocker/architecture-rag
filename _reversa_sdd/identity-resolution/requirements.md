# ingestion/identity-resolution: требования

> Writer R.6a. Алиас путей: `IR` = `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution`, `MIG` = `ingestion/identity-resolution/src/main/resources/db/migration`, `IT` = `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests`. Метки: 🟢 подтверждено кодом, 🟡 выведено, 🔴 пробел. Use case: [`gid-mapping-crosswalk/`](gid-mapping-crosswalk/requirements.md), [`candidate-matching/`](candidate-matching/requirements.md), [`source-conflicts/`](source-conflicts/requirements.md).

## Обзор

Модуль хранит в PostgreSQL всё, что относится к идентичности записей источников, и ничего не пишет в граф. Три ступени политики ADR 0019 (без auto-merge): (1) deterministic mapping `(source, sourceType, sourceId) -> gid` и approved crosswalk; (2) кандидаты на совпадение по признакам (только запись подозрения); (3) конфликты источников по `AuthorityMatrix` (только фиксация расхождения). Три порта (`IdentityMapping`, `IdentityCandidates`, `SourceConflicts`) и их JDBC-реализации без ORM, чистые функции `FeatureExtractor`/`FeatureNormalizer`/`FeatureKind`/`LabelFamily`, миграции Flyway `V2`, `V4`, `V5`, `V7` 🟢 (`IR/package-info.java:1-19`).

## Ответственности

- Выдать `gid` ключу один раз и навсегда; параллельные вызовы сходятся к одному значению 🟢 (`IR/IdentityMapping.java:8-14`, `IR/PostgresIdentityMapping.java:45-60,124-132`).
- Применять approved crosswalk: общий `gid` двум ключам; разные уже назначенные `gid` дают `CrosswalkConflictException`, слияния нет 🟢 (`IR/PostgresIdentityMapping.java:80-122`).
- Снимать признаки идентичности с команд события и хранить их текущий набор на ключ 🟢 (`IR/FeatureExtractor.java:43-69`, `IR/PostgresIdentityCandidates.java:239-264`).
- Записывать кандидатов на совпадение со score; узлы не объединять, `gid` не менять 🟢 (`IR/IdentityCandidates.java:8-15`).
- Хранить утверждения свойств каждой записи и вычислять конфликты «неавторитетное значение против мастера»; закрытый конфликт остаётся `RESOLVED` 🟢 (`IR/PostgresSourceConflicts.java:21-32,210-271`).
- Поддерживать конфликты вида `RELATION` (ADR 0030/0031) через `assertRelation` 🟢 (`IR/SourceConflicts.java:30-48`).

Вне модуля 🟢: вызывающая сторона (`EventProcessor`, `Reconciler`, `CrosswalkService`), выбор canonical-значения (проектор), чтение консолью, права `archrag_console_ro` (V6), применение Flyway (`JournalMigrations` в `event-journal`).

## Регламент (бизнес-правила)

- BR-01. `gid` ключа неизменен; повторный `resolve` и rebuild графа возвращают тот же `gid` 🟢 (`IR/IdentityMapping.java:11-13`; IT `IdentityMappingIT.repeatedResolveReturnsSameGidWithNewMappingInstance`).
- BR-02. Один `sourceId` в разных системах/типах — разные сущности: ключ целиком `(source, source_type, source_id)` 🟢 (`MIG/V2__identity_mapping.sql:9`; IT `sameSourceIdInDifferentSystemsIsNotSameEntity`).
- BR-03. Crosswalk не сливает: ключи с разными `gid` дают исключение, состояние не меняется 🟢 (`IR/PostgresIdentityMapping.java:107-109,113-116`; IT `crosswalkOfKeysWithDifferentGidsIsRejectedAndChangesNothing`).
- BR-04. Crosswalk идемпотентен и не зависит от порядка пары: `lo < hi` по `SourceKeys.compare` 🟢 (`IR/PostgresIdentityMapping.java:84-86`, `IR/SourceKeys.java:10-16`; IT `crosswalkIsIdempotentAndOrderInsensitive`).
- BR-05. Score = `min(1.00, сумма весов различных видов совпавших признаков)`, шкала 2, `HALF_UP`; веса `HOSTNAME 0.6`, `REPOSITORY_URL 0.8`, `NAME 0.4`, `OWNER 0.2` 🟢 (`IR/FeatureKind.java:15-18,38-47`; тест `FeatureExtractorTest.ownerIsNotStrongAndScoreIsCappedSum`). Веса в коде, не в конфигурации 🟢; ADR числовых параметров не фиксирует 🟡 (`_reversa_sdd/adrs/retroactive-map.md:28`, К-6).
- BR-06. Кандидат появляется только при совпадении хотя бы одного сильного признака (`HOSTNAME`, `REPOSITORY_URL`, `NAME`); `OWNER` сам пару не создаёт, лишь усиливает 🟢 (`IR/PostgresIdentityCandidates.java:353-400`; IT `sameOwnerWithDifferentNamesGivesNoCandidate`).
- BR-07. Сравнение только точное по нормализованным значениям внутри семейства меток (`VIRTUAL_MACHINE`/`PHYSICAL_SERVER` и иные подтипы `ComputeInstance` = `COMPUTE_INSTANCE`; прочие метки сами по себе) 🟢 (`IR/LabelFamily.java:10-12`; IT `itSystemAndServiceWithSameNameAreNotCandidates`).
- BR-08. Записи с общим `gid` кандидатов не дают 🟢 (`IR/PostgresIdentityCandidates.java:209-211`; IT `recordsLinkedByCrosswalkShareGidAndGiveNoCandidate`).
- BR-09. Предохранитель: у сильного признака больше `MAX_MATCHES = 50` других записей — кандидаты по нему не создаются, WARN с видом, семейством и пределом, без значения 🟢 (`IR/PostgresIdentityCandidates.java:195,374-378`; IT `popularFeatureAboveLimitCreatesNoCandidates`).
- BR-10. Повторное наблюдение идемпотентно: `first_seen_at` и `status` не меняются, `matched`, `score`, `updated_at` обновляются 🟢 (`IR/PostgresIdentityCandidates.java:216-223`; IT `replayKeepsOneCandidateAndFirstSeenAt`).
- BR-11. `forget` удаляет признаки ключа, кандидаты остаются историей 🟢 (`IR/IdentityCandidates.java:27`, `IR/PostgresIdentityCandidates.java:266-282`; IT `tombstonedRecordDoesNotMatchLaterRecord`).
- BR-12. Конфликт `PROPERTY` существует, только если у свойства есть авторитетное утверждение и неавторитетное с иным значением (точное равенство строк); мастер при нескольких авторитетных — первый по `(source, source_type, source_id)` 🟢 (`IR/PostgresSourceConflicts.java:41-47`; IT `differingNonAuthoritativeValueOpensOneConflict`, `onlyNonAuthoritativeAssertionsGiveNoConflict`).
- BR-13. Результат не зависит от порядка прихода мастера и неавторитетного источника 🟢 (`IR/SourceConflicts.java:13-17`; IT `conflictDoesNotDependOnArrivalOrder`).
- BR-14. Строка `source_conflict` не удаляется; перестав расходиться, получает `RESOLVED` + `resolved_at`, при новом расхождении снова `OPEN`, `opened_at` сохраняется, `resolved_at = NULL` 🟢 (`IR/PostgresSourceConflicts.java:48-58,61-63`; IT `equalValueResolvesAndReopeningKeepsOpenedAt`).
- BR-15. `RELATION`: пустой `master` или равные значения закрывают строку, расхождение — upsert `OPEN`; `PROPERTY` и `RELATION` не трогают друг друга при пересчёте 🟢 (`IR/PostgresSourceConflicts.java:105-119,59-60`; IT `relationClosesWhenMasterUnknownAndNeverOpensWithoutIt`, `retractResolvesRelationAndPropertyRecomputeLeavesItAlone`).
- BR-16. Значения источника (недоверенный текст) не логируются и не попадают в `SourceConflict.toString()` и в исключения 🟢 (`IR/SourceConflict.java:77-81`, `IR/PostgresIdentityCandidates.java:374-377`).
- BR-17. `identity_candidate.status` не выходит из `OPEN`, значений-перечисления нет 🔴 (`IR/IdentityCandidate.java:20`, `MIG/V4__identity_candidate.sql:31`; `_reversa_sdd/detective-gaps.md:22` Р-4).

## Функциональные требования

| ID | Требование | MoSCoW | Критерий приёмки |
|----|-----------|--------|------------------|
| RF-01 | `resolve(key)` возвращает существующий `gid` или создаёт новый; `find` ничего не создаёт | Must | `gid-mapping-crosswalk` сц. 1-2 |
| RF-02 | `approve(crosswalk)` даёт обоим ключам общий `gid`, пишет `approved_crosswalk`, одной транзакцией | Must | `gid-mapping-crosswalk` сц. 3-6 |
| RF-03 | `keysOf(gid)` возвращает ключи, упорядоченные по `source, source_type, source_id` | Should | `IdentityMappingIT.crosswalkGivesEamAndScmRecordsOneGid` |
| RF-04 | `FeatureExtractor.extract` снимает `HOSTNAME`/`REPOSITORY_URL`/`NAME` по метке и `OWNER` по `OWNED_BY` | Must | `candidate-matching` сц. 1 |
| RF-05 | `IdentityCandidates.record` заменяет признаки ключа, находит пары, upsert кандидатов; `candidatesOf` читает | Must | `candidate-matching` сц. 2-5 |
| RF-06 | `forget` удаляет признаки ключа | Should | `IdentityCandidateIT.tombstonedRecordDoesNotMatchLaterRecord` |
| RF-07 | `SourceConflicts.assertProperties` заменяет утверждения записи и пересчитывает конфликты `gid` | Must | `source-conflicts` сц. 1-3 |
| RF-08 | `assertRelation` ведёт конфликты `RELATION` | Should | `source-conflicts` сц. 4 |
| RF-09 | `retract` убирает утверждения записи, закрывает её `RELATION`-конфликты, пересчитывает | Must | `PostgresSourceConflictsIT.retractOfDissentResolvesAndRetractOfMasterResolvesToo` |
| RF-10 | Любой отказ SQL превращается в `IdentityStoreException` с исходной причиной | Must | `candidate-matching` сц. 6 (🟡 нет теста) |
| RF-11 | Схема создаётся миграциями `V2`, `V4`, `V5`, `V7` (аддитивные, без CHECK и FK) | Must | см. [`contracts.md`](contracts.md) |

## Нефункциональные требования

Только то, что подтверждено кодом.

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Консистентность | Каждая публичная операция портов — одна транзакция (`setAutoCommit(false)`, `commit`, при `SQLException`/`RuntimeException` `rollback`); исключение прокидывается | `IR/PostgresIdentityMapping.java:47-59,87-100`; `IR/PostgresIdentityCandidates.java:243-263`; `IR/PostgresSourceConflicts.java:146-160` | 🟢 |
| Конкурентность | Сериализация advisory-lock'ами `pg_advisory_xact_lock(hashtextextended(?, 0))` на ключи `key\|<SourceKey>`, `<семейство>\|<вид>\|<значение>` (сильные признаки), `conflict\|<gid>`; блокировка снимается с концом транзакции | `IR/PostgresIdentityCandidates.java:199,246-247,316-331`; `IR/PostgresSourceConflicts.java:35,162-167` | 🟢 |
| Конкурентность | Нет взаимной блокировки в `record`: признаки отсортированы (`kind`, затем `value`), сначала лок самого ключа, затем значений | `IR/PostgresIdentityCandidates.java:242,316` | 🟢 |
| Конкурентность | `gid` создаётся `INSERT ... ON CONFLICT DO NOTHING` и перечитывается: гонка даёт один `gid` | `IR/PostgresIdentityMapping.java:27-28,124-132`; IT `IdentityMappingIT.concurrentResolveConvergesToOneGid` | 🟢 |
| Безопасность | SQL только через `PreparedStatement` с параметрами, конкатенации входа нет (константы SQL склеиваются только из литералов) | `IR/PostgresIdentityMapping.java:25-28,65`; `IR/PostgresIdentityCandidates.java:199-231`; `IR/PostgresSourceConflicts.java:36-71` | 🟢 |
| Безопасность | Значения источников не попадают в логи, `toString`, сообщения исключений-причин для оператора | `IR/SourceConflict.java:77-81`; `IR/PostgresIdentityCandidates.java:374-377` | 🟢 |
| Эксплуатация | Предел `MAX_MATCHES = 50`, запрос с `LIMIT 51` | `IR/PostgresIdentityCandidates.java:195,367` | 🟢 |
| Время | Метки времени пишутся `Timestamp.from(Instant.now())` в `timestamptz` | `IR/PostgresIdentityMapping.java:128`; `MIG/V2__identity_mapping.sql:8` | 🟢 |

Таймаутов запросов, пула, retry на уровне модуля нет: повтор делает вызывающий (`EventProcessor.retry`) 🟢 (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:232-235,246-248`).

## Критерии приёмки (модульные)

```gherkin
Дано ключ EAM/IT_SYSTEM/PAY ещё не отображён
Когда вызван resolve дважды, в том числе новым экземпляром IdentityMapping
Тогда оба раза возвращается один и тот же gid

Дано EAM/IT_SYSTEM/PAY и SCM/CATALOG_SYSTEM/42 уже имеют разные gid
Когда применяется crosswalk между ними
Тогда бросается CrosswalkConflictException, identity_mapping и approved_crosswalk не изменены

Дано две записи одного семейства с одинаковым нормализованным hostname и разным gid
Когда для второй вызван record
Тогда в identity_candidate одна строка status = OPEN, score = 0.60

Дано мастер и неавторитетный источник утверждают разные значения свойства одного gid
Когда утверждения приходят в любом порядке
Тогда открыт ровно один PROPERTY-конфликт; при совпадении значений он RESOLVED, а не удалён
```

Подробные сценарии, включая негативные, — в use case.

## Прочие 🔴

- Нет модульных тестов Postgres-реализаций: их покрывают только IT в `platform/integration-tests` (`mvn -pl ingestion/identity-resolution verify` запускает 20 `@Test` в 4 классах: `FeatureExtractorTest` 8, `FeatureNormalizerTest` 7, `CrosswalkTest` 4, `IdentityResolutionSmokeTest` 1) 🟢 (`_reversa_sdd/tech-debt.md:66` Н-11).
- Остальные 🔴 — [`questions.md`](questions.md).

## Прочие требования

Зависимости: `contracts/canonical-model` (compile), `org.postgresql:postgresql` (runtime), версии через BOM 🟢 (`ingestion/identity-resolution/pom.xml:17-27`). Spring, Flyway и тестовые зависимости модуль сам не объявляет 🟢 (наследуются или не нужны; ни один класс не использует Spring).

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| `IdentityMapping.resolve/approve` | Must | gid нужен projector'у на каждом событии (`EventProcessor.java:588`) |
| `SourceConflicts.assertProperties/retract` | Must | вызывается на каждом upsert/delete записи |
| `IdentityCandidates.record/forget` | Must | вызывается на каждом событии; отказ -> `RETRYING` |
| `assertRelation`, `candidatesOf`, `keysOf`, `openConflicts` | Should | узкие потребители (ADR 0030/0031, консоль, тесты) |
| Перечисление `status` кандидата, переходы | Won't (сейчас) | не реализовано, 🔴 |

## Рассмотренные / не вошедшие

Нет нечёткого сравнения, authoritative-weights из конфигурации, ручного решения конфликта, auto-merge (ADR 0019) 🟢 (`IR/package-info.java:10-17`).

## Трассировка кода

| Файл | Класс | Покрытие спецификацией |
|------|-------|------------------------|
| `IR/IdentityMapping.java`, `IR/PostgresIdentityMapping.java`, `IR/Crosswalk.java`, `IR/CrosswalkConflictException.java`, `IR/SourceKeys.java` | mapping, crosswalk | `gid-mapping-crosswalk/` 🟢 |
| `IR/IdentityCandidates.java`, `IR/PostgresIdentityCandidates.java`, `IR/IdentityCandidate.java`, `IR/Feature.java`, `IR/FeatureKind.java`, `IR/FeatureExtractor.java`, `IR/FeatureNormalizer.java`, `IR/LabelFamily.java` | кандидаты | `candidate-matching/` 🟢 |
| `IR/SourceConflicts.java`, `IR/PostgresSourceConflicts.java`, `IR/SourceConflict.java` | конфликты | `source-conflicts/` 🟢 |
| `IR/IdentityStoreException.java`, `IR/IdentityResolutionModule.java`, `IR/package-info.java` | общее | модульные файлы 🟢 |
| `MIG/V2`, `V4`, `V5`, `V7` | схема | [`contracts.md`](contracts.md) 🟢 |
