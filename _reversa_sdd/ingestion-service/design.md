# ingestion/ingestion-service: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md).
> Use case-спецификации: [`dispatch-journal/`](dispatch-journal/design.md), [`replay-rebuild/`](replay-rebuild/design.md), [`reconcile-source/`](reconcile-source/design.md), [`load-crosswalk/`](load-crosswalk/design.md).
> Сквозные части (`AdminOperations`, `AdminLock`, `AdminAudit`, безопасность, метрики, старт) описаны здесь и в use case не дублируются.

## Интерфейс

Пакет `io.github.unlocker.archrag.ingestionservice`. HTTP-контракт — в [`contracts.md`](contracts.md) §1.

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `JournalDispatcher.runOnce` | `() -> int` | число событий, взятых в обработку; не бросает по ошибке одного события | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:94-130` 🟢 |
| `JournalDispatcher.stop` | `() -> void` (package-private) | `stopped = true`, обратно не сбрасывается | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:63, 89-91` 🟢 |
| `StoredEventReader.toEvent` | `(StoredEvent, byte[] raw) -> CanonicalEvent` | `IllegalArgumentException` без содержимого raw | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:44-86` 🟢 |
| `AdminOperations.run` | `(operation, actor, Map request, replayId, Supplier<T> body, Function<T,Map> summary) -> T` | `AdminBusyException` при занятом замке; `IllegalStateException` при сбое записи `STARTED`; исключение `body` пробрасывается | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:38-61` 🟢 |
| `AdminOperations.locked` | `(Supplier<T>) -> T` | тот же замок, без общей записи аудита | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:72-76` 🟢 |
| `AdminLock.acquire` / `tryAcquire` | `() -> Lease` / `() -> Optional<Lease>` | `acquire` бросает `AdminBusyException`; `tryAcquire` пуст при занятом замке; `IllegalStateException("admin lock failed")` при SQL-ошибке | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java:32-56` 🟢 |
| `AdminAudit.start` / `finish` | `(operation, actor, Map request, replayId) -> long` / `(id, succeeded, Map result, error)` | `IllegalStateException("audit write failed")` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java:33-64` 🟢 |
| `ReplayService.replay` | `(source?, from?, to?, replayId) -> ReplayResult` | см. [`replay-rebuild/design.md`](replay-rebuild/design.md) | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:63-76` 🟢 |
| `RebuildService.rebuild` | `(replayId) -> ReplayResult` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:33-39` 🟢 |
| `CrosswalkService.load` | `(List<CrosswalkItem>, approvedBy) -> List<CrosswalkResult>` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:35-41` 🟢 |
| `AdapterControlClient.snapshot` / `knows` / `code` | см. [`reconcile-source/design.md`](reconcile-source/design.md) | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:33-72` 🟢 |
| `IngestionMetrics.recordEvent` / `recordWarnings` / `refresh` | `(String source, ProcessingStatus)` / `(SourceSystemCode, List<String>)` / `()` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:100-147` 🟢 |

Исключения-маркеры (пакет): `BadRequestException(String)` → 400; `AdminBusyException()` («another admin operation is in progress») → 409; `AdapterUnavailableException()` («adapter is unavailable») → 502 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:125-138`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminBusyException.java:4-8`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterUnavailableException.java:4-8`) 🟢.

## Старт приложения (сборка бинов)

`IngestionServiceConfiguration` (`proxyBeanMethods = false`, `@EnableScheduling`) собирает граф зависимостей вручную 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:40-42`). Порядок, важный для корректности:

1. `eventJournal(DataSource)`: **`JournalMigrations.apply(dataSource)`** (Flyway, `classpath:db/migration`), затем `new PostgresEventJournal(dataSource)` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:45-49`). Boot-Flyway не подключён (`ingestion/ingestion-service/pom.xml:50-54`).
2. `identityMapping`, `identityCandidates`, `sourceConflicts` получают `PostgresEventJournal migrated` параметром, чтобы миграции прошли раньше (`:51-65`). `sourceConflicts` создаётся с `AuthorityMatrix.defaults()` (`:64`).
3. `neo4jDriver` (`destroyMethod = close`): `GraphDatabase.driver(uri, basic(username, password))` → **`Neo4jSchema.apply(driver)`** → **`new TemporalRelationMigration(driver).migrate(Instant.now())`** (ADR 0028, идемпотентно) (`:67-74`). Учётные данные writer (`Neo4jProperties`).
4. `rawPayloadStore` (`destroyMethod = close`): `S3RawPayloadStore.create(URI.create(endpoint), accessKey, secretKey, bucket)` + **`ensureBucket()`** (`:76-82`).
5. `graphProjector(Driver)`: `new GraphProjector(driver, AuthorityMatrix.defaults())` (`:84-87`); второй экземпляр `AuthorityMatrix.defaults()`.
6. `reconciler(...)`: `new Reconciler(journal, rawStore, projector, Clock.systemUTC(), identity, conflicts)` (`:89-98`).
7. `eventProcessor(...)`: `new EventProcessor(journal, Normalizer.standard(projector::isActive, projector), identity, projector, reconciliation, candidates, conflicts, projector, projector, metrics::recordWarnings)` (`:100-112`).
8. `ingestionMetrics(MeterRegistry, JdbcTemplate, PostgresEventJournal)` (`:114-119`).
9. `journalDispatcher` — `@ConditionalOnProperty(archrag.dispatcher.enabled, havingValue=true, matchIfMissing=true)`; `Clock.systemUTC()`, `props.batchSize()`, `props.retryDelay()` (`:121-135`). `DispatcherRunner` имеет то же условие (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:17`).
10. `@ConfigurationPropertiesScan` на приложении подхватывает `DispatcherProperties`, `AdaptersProperties`, `Neo4jProperties`, `S3Properties`; `AdminProperties` включается через `@EnableConfigurationProperties` в `SecurityConfiguration` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceApplication.java:8-9`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:24`) 🟢.

🟡 Побочные эффекты старта (миграции, создание схемы Neo4j, `migrate`, `ensureBucket`) выполняются в фабричных методах бинов, а не в транзакциях драйвера: правило «внутри `executeWrite` нет внешних эффектов» не нарушается (эффекты вне transaction function).

## Безопасность админского REST

- `SecurityFilterChain adminSecurity`: `csrf` отключён; `STATELESS`; `/admin/**` → `hasAuthority("SCOPE_architecture.admin")`; остальное `denyAll()`; `oauth2ResourceServer(jwt)` с настройками по умолчанию (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:36-46`) 🟢. Scope приходит как authority `SCOPE_<scope>` из стандартного конвертера Spring 🟡.
- `OAuth2TokenValidator<Jwt> authorizedPartyValidator` — бин, который автоконфигурация декодера добавляет к проверкам issuer и audience (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:30-34`) 🟢. Проверка: `azp != null && allowedClients.contains(azp)`, иначе `OAuth2Error("invalid_token", "The authorized party is not allowed for this resource")` → 401 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java:16-31`) 🟢.
- `AdminProperties.allowedClients`: по умолчанию `archrag-demo`; пустой список → `IllegalArgumentException` при старте (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminProperties.java:13-20`) 🟢. Источник значения — `ARCHRAG_ADMIN_ALLOWED_CLIENTS` (`application.yml:22`).
- Актор операции — `jwt.getSubject()` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:77, 90, 112, 122`) 🟢.
- Исключений безопасности для `/actuator` нет: он закрыт `denyAll` (комментарий `ingestion/ingestion-service/src/main/resources/application.yml:51-52`), хотя `management.endpoints.web.exposure.include: health` задан (`:62-65`) 🟢. Healthcheck compose поэтому проверяет «любой HTTP-ответ» (`platform/docker/docker-compose.yml:213-217`).

## Единый путь админской операции (`AdminOperations`)

1. `lock.acquire()` (не ждёт; занят → `AdminBusyException` → 409).
2. `audit.start(operation, actor, request, replayId)` → `id`. Сбой → `IllegalStateException`, тело не выполняется.
3. `LOG.info("admin operation started: operation= actor= replayId=")`.
4. `body.get()`; `catch (Throwable e)` → `finishQuietly(id, false, null, e.getClass().getSimpleName())`, `LOG.warn(..., error=<SimpleName>)`, `throw e`.
5. Успех: `finishQuietly(id, true, summary.apply(result), null)`; сбой записи итога → `LOG.error("admin audit finish failed: auditId= error=")`, клиент получает результат.
6. Замок освобождается в `try-with-resources` (`Lease.close`) 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:45-61`, `:63-69`).

Особенности:
- 🟢 `summary.apply(result)` вычисляется **вне** `finishQuietly` и вне `catch` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:57`): исключение функции summary даст клиенту 500 при уже выполненной операции, запись останется `STARTED` (не покрыто тестом) 🟡.
- 🟢 `CROSSWALK` идёт через `locked(...)` без общей записи; аудит ведёт `CrosswalkService` по элементам (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:122`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:71-76`).

### `AdminLock`

`pg_try_advisory_lock(KEY)` на **отдельном соединении** из `DataSource`, которое удерживается до `Lease.close()`; `close()` выполняет `pg_advisory_unlock(KEY)` и закрывает соединение (`try (connection; ps)`). `KEY = 0x41524348_41444D31L` (ASCII `ARCH`+`ADM1`) (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java:19, 37-56, 69-86`) 🟢. 🟡 Если `pg_advisory_unlock` бросит `SQLException`, соединение всё равно закрывается (возвращается в пул) с сессионным замком в живой сессии — риск «залипшего» замка; в коде не обрабатывается (Q-09).

### `AdminAudit`

`INSERT INTO admin_audit (operation, actor, request, replay_id, status, started_at) VALUES (?, ?, ?::jsonb, ?, 'STARTED', ?) RETURNING id`; `UPDATE admin_audit SET status = ?, result = ?::jsonb, error = ?, finished_at = ? WHERE id = ?`; `error` усекается до `MAX_ERROR_LENGTH = 200` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java:22, 33-68`) 🟢. Время — `Timestamp.from(Instant.now())` (UTC-инстант) 🟢.

## Метрики (`IngestionMetrics`)

- Счётчик `archrag.ingestion.events{source,status}`: все пары `source ∈ SourceSystem.code() ∪ {unknown}` × (`ProcessingStatus` ∪ `ERROR`) регистрируются в конструкторе с нулём 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:78-92`). `recordEvent(source, status)`: `status == null` → `ERROR` (`:118-120`).
- Gauges `archrag.ingestion.sync.lag` (секунды, самый старый ожидающий `inbox_event` по `source`) и `archrag.ingestion.dlq.open` (`dlq_entry WHERE replayed_at IS NULL`) обновляются `@Scheduled(fixedDelayString = "${archrag.metrics.refresh-interval:15s}")`; ожидающие статусы: `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, RETRYING` (`:62-71, 131-147`) 🟢. `DataAccessException` → `LOG.warn` с классом, прежние значения сохраняются.
- Счётчик `archrag.normalizer.unmapped.values{source,property}`: растёт на предупреждения нормализатора из таблицы `UNMAPPED_PROPERTIES = {UNMAPPED_CRITICALITY → criticality}`; предрегистрируется для `EAM`/`criticality`. Источник сообщений — `EventProcessor` (`metrics::recordWarnings`) (`:50-52, 93, 100-112`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:110-111`) 🟢.
- `sourceTag`: `urn:corp:eam` → `eam`; код вне `SourceSystem` → `unknown` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:150-154`) 🟢. Теги `source` счётчика предупреждений — `SourceSystemCode.name().toLowerCase()` (`:109-112`); для `DEPLOYMAP` и `deploymap` совпадает с `SourceSystem.DEPLOY_MAP.code()` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java:9`, `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SourceSystemCode.java:4-6`) 🟢.
- 🔴 Метрика `dlq.open` не уменьшается (колонка `replayed_at` нигде не пишется) — см. [`questions.md`](questions.md) Q-03.

## Конфигурация

Полный перечень ключей и переменных окружения — [`contracts.md`](contracts.md) §4. Значения секретов не приводятся.

## Зависит от / Связанные спецификации

Источник: [`../traceability/spec-impact-matrix.md`](../traceability/spec-impact-matrix.md) §1, §2.

**Прямые зависимости (pom, §1)** — меняются они, пересматривается эта спецификация:

| Модуль | Что берёт `ingestion-service` | Спецификация |
|--------|-------------------------------|--------------|
| `contracts/event-schemas` | `CanonicalEvent`, `ProcessingStatus`, `EventJournal`, `JournalReader`, `JournalQuery`, `RawPayloadStore`, `StoredEvent` | [`../event-schemas/design.md`](../event-schemas/design.md) |
| `contracts/canonical-model` | `AuthorityMatrix.defaults()`, `SourceKey`, `SourceSystemCode` | [`../canonical-model/design.md`](../canonical-model/design.md) |
| `contracts/source-spi` | `SourceSystem.code()` (метрики) | [`../source-spi/design.md`](../source-spi/design.md) |
| `ingestion/event-journal` | `PostgresEventJournal`, `S3RawPayloadStore`, `JournalMigrations`, Flyway V1 | [`../event-journal/design.md`](../event-journal/design.md) |
| `ingestion/normalizer` | `Normalizer.standard(...)` | [`../normalizer/design.md`](../normalizer/design.md) |
| `ingestion/identity-resolution` | `IdentityMapping.approve`, `Crosswalk`, Postgres-реализации, Flyway V2/V4/V5/V7 | [`../identity-resolution/design.md`](../identity-resolution/design.md), [`../identity-resolution/gid-mapping-crosswalk/design.md`](../identity-resolution/gid-mapping-crosswalk/design.md) |
| `ingestion/graph-projector` | `EventProcessor`, `GraphProjector`, `Reconciler`, `Neo4jSchema`, `TemporalRelationMigration` | [`../graph-projector/`](../graph-projector/) (спецификация пишется параллельно; ссылка на папку) |
| `adapters/adapter-core` (только `test`) | `EventMapper` для `StoredEventReaderContractTest` | [`../adapter-core/design.md`](../adapter-core/design.md) |

**Через данные и протокол (§1, строка `ingestion/ingestion-service`)**: admin REST (оператор, `restore.sh`); Flyway `V3`/`V6`; админ-консоль `access/admin-console` (читает `inbox_event`, `dlq_entry`, `consumer_checkpoint`, `identity_candidate`, `source_conflict`, `admin_audit` ролью `archrag_console_ro`; отдельной спецификации модуля нет, см. [`../flowcharts/admin-console.md`](../flowcharts/admin-console.md) и `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java`). Adapter-сервисы: `POST /control/snapshot` (см. [`reconcile-source/design.md`](reconcile-source/design.md), сторона адаптера — [`../adapter-core/full-snapshot/design.md`](../adapter-core/full-snapshot/design.md)).

**Неявные связи (§2), затрагивающие модуль:**

| № | Связь | Где в модуле | Защита |
|---|-------|--------------|--------|
| 1 | Литералы типов `snapshot-complete` / `deleted` продублированы | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:34`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:37` ↔ `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java:29-32`, `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:92-94` | нет теста на имена типов 🔴 (Q-05) |
| 5 | `GRANT SELECT` консоли ↔ SQL консоли | `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15-16` | нет теста 🟡 |
| 6 | Формат raw JSON: запись `EventMapper` ↔ чтение `StoredEventReader` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:44-86` | `StoredEventReaderContractTest` 🟢 |
| 12 | Три экземпляра `AuthorityMatrix.defaults()` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:64, 86` (+ `ExplainProvenanceTool`) | конфигурации матрицы нет 🟢 |
| 14 | Каталог `db/migration` разделён тремя модулями | `V3`, `V6` модуля ↔ `V1` event-journal, `V2/V4/V5/V7` identity-resolution | сквозная нумерация; конфликт версий ломает старт 🟡 |

Дополнительно: формат `Reconciler`-tombstone raw (№ 7) читается `StoredEventReader`, контрактный тест `reconcilerTombstoneRawMatchesEventMapperDelete` (`ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/adaptercore/StoredEventReaderContractTest.java:85`) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Один диспетчер, один поток, замок на страницу, а не на проход | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:24, 99-128`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:36-44` | 🟢 |
| Админская операция синхронная, под тем же замком, что и диспетчер | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:45`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:75-81` | 🟢 |
| `CALL IN TRANSACTIONS` через `session.run`, а не `executeWrite` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:34-36` | 🟢 |
| Ошибки одного события не останавливают проход; в лог только класс исключения | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:156-162` | 🟢 |
| Проблемные строки в DLQ отправляет диспетчер (отступление от «DLQ ведёт `EventProcessor`») | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:40-43` | 🟢 |
| Сборка бинов вручную, Boot-Flyway отключён | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:36-38`; `ingestion/ingestion-service/pom.xml:50-54` | 🟢 |
| `azp` allow-list поверх scope (ADR 0026) | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java:9-13`, [`decisions.md`](decisions.md) | 🟢 |
| `adapter-core` подключён только со `scope=test` | `ingestion/ingestion-service/pom.xml:71-76` | 🟢 |

## Estado Interno

Состояния в процессе нет, кроме: `JournalDispatcher.stopped` (volatile, не сбрасывается), `blocked`/`after` (локальные в `runOnce`), `DispatcherRunner.executor`, метрики Micrometer, `HttpClient` в `AdapterControlClient`. Всё остальное — в PostgreSQL (`inbox_event`, `dlq_entry`, `admin_audit`, advisory lock), S3 и Neo4j 🟢.

## Riscos e Lacunas

- 🔴 Нет лимита повторов `RETRYING`; постоянная ошибка повторяется бесконечно — Q-01.
- 🔴 `QUARANTINED` — тупик для оператора (REST-операции нет) — Q-02.
- 🔴 `dlq_entry.replayed_at` не пишется — Q-03.
- 🔴 Replay/rebuild прерываются на первой «битой» строке журнала — Q-04, [`replay-rebuild/design.md`](replay-rebuild/design.md).
- 🔴 Литералы типов событий без общего источника и без теста — Q-05.
- 🟡 `JournalDispatcher.stop()` необратим: повторный `DispatcherRunner.start()` создаст executor, но `runOnce` сразу вернёт 0 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:63, 89-91, 99`) — Q-09.
- 🟡 Замок занимается диспетчером на время страницы; админская операция, пришедшая в этот момент, получает 409, а не ждёт. `docs/03-interfaces.md:386` говорит, что диспетчер «уступает» — это верно только между страницами. `AdminEndpointIT` отключает диспетчер (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:101`), конкуренция не тестируется — Q-06.
- 🟡 Контейнерная проверка готовности опирается на «любой HTTP-ответ» (`platform/docker/docker-compose.yml:213-217`); комментарий compose «нет actuator» неточен (стартер подключён, `ingestion/ingestion-service/pom.xml:56-60`) — уже отмечено в `../code-analysis.md:1951`.
