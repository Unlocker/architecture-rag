# Лакуны, оставшиеся после ревью (R.7)

> Reversa Reviewer, UNLOCKER-273. `doc_level = completo`. Вопросы владельцу — в [`questions.md`](questions.md); сводная оценка — в [`confidence-report.md`](confidence-report.md). Код и `docs/` не правились.

Серьёзность по шкале Reversa: **критическая** — реимплементация по спеке даст поведение, отличное от кода; **умеренная** — неточность формулировки или классификации; **косметическая** — нумерация, ссылки.

## 1. Решения владельца получены (R.8; было 🔴, см. `questions.md`)

| № | Тема | Вопрос |
|---|------|--------|
| 1–6 | Жизненные циклы: `SyncRun`, DLQ/`QUARANTINED`, `identity_candidate`, правило закрытия узла, `CloseAssertion`, `snapshot-complete` без счётчика | A |
| 7–10 | Расхождения код ↔ docs/ADR, loopback, дубли значений, зависимость `source-spi` | B |
| 11–14 | Безопасность, retention/SLO, реальные источники, покрытие тестами | C |
| 15–20 | `Reconciler` и `forget`, OIDC `state`, аудит фильтра, `systemGid`, «устарел», `MANUAL` | D |

## 2. Исправлено в specs в рамках R.7 (метка `[Ревью R.7]`)

| Файл | Что исправлено |
|------|----------------|
| `state-machines.md` (инвариант DLQ) | повторный `toDlq` из `QUARANTINED` возможен (🟡 → 🟢) |
| `event-schemas/requirements.md` | реализация `CanonicalEventPublisher` есть (`PostgresEventJournal`), нет вызовов (🟡 → 🟢); ссылки `pom.xml:17-22` |
| `admin-ui/requirements.md`, `admin-ui/questions.md` Q-08 | `state` OIDC в `localStorage` по умолчанию библиотеки; расхождение с ADR 0026 (🟡 → 🟢 факт) |
| `canonical-model/decisions.md` | «`contracts/*` не зависят» сужено до `canonical-model` |
| `source-spi/design.md` | реализации порта привязаны к верным модулям |
| `dependencies.md` | test-scope рёбра (`ingestion-service → adapter-core`, `deploymap-adapter → canonical-model`), `<exclusions>` у `s3` |
| `traceability/spec-impact-matrix.md` | прямые потребители `canonical-model`, `event-schemas`, `source-spi` по pom |

## 3. Не исправлено, требует правки автором specs (умеренные и косметические)

Формат: файл — проблема — доказательство — предложение.

### Умеренные (классификация или формулировка)
- `identity-resolution/candidate-matching/{requirements,design}.md` — ссылки `:352-400`, `:383-398`, `:316-427` выходят за 277 строк `PostgresIdentityCandidates.java`; корректно: `record` 77-102, `forget` 104-120, `candidatesOf` 122-152, `replaceFeatures` 171-188, `findMatches` 191-239, `upsertCandidate` 241-265. 🟢 → 🟡 до перенумерации.
- `identity-resolution/design.md:148` — «повтор безопасен» (🟢): `recordCandidates`/`forget` вызываются до `ignoreIfOlderOnRetry` (`EventProcessor.java:232` до `:237`, `:271` до `:275`); устаревшее `RETRYING` перезапишет `identity_feature`. → 🟡; `graph-projector/questions.md` Q-10 → 🟢 факт.
- `identity-resolution/requirements.md:62` — «каждая публичная операция портов в одной транзакции» (🟢) против `design.md:135` (чтения без транзакции). Порядок `keysOf` (SQL `ORDER BY`) и `SourceKeys.compare` (Java) различается.
- `identity-resolution/source-conflicts` — PK `source_conflict` без `kind` (`V5__source_conflict.sql:64`): конфликты `PROPERTY` и `RELATION` с одинаковым `(gid, property, dissent)` перезапишут друг друга (практически маловероятно) → 🟡-ограничение.
- `admin-ui`/`admin-console` — фильтр `replayed=false` подан как рабочий (🟢); `replayed_at` нигде не пишется (`V1__event_journal.sql:43`, `PostgresEventJournal.java:142`, `IngestionMetrics.java:71`) → 🔴 «не работает» (вопрос 2).
- `admin-console/requirements.md` — «единый формат ошибок» (🟢): обработчиков четыре (`ApiExceptionHandler.java:23-41`); `Neo4jException`, `IllegalStateException`, ошибки БД уходят в стандартный 500 → 🟡. `contracts.md` §1.1 «всегда 4 элемента» — см. вопрос 20.
- `admin-console/decisions.md:18` — «расхождения кода с ADR: не найдены» при открытых Q-01 (loopback) и EXPLAIN (ADR 0005:22, `GraphQueryExecutor.java:99-124`). Статусы EXPLAIN-расхождения различаются в gqc Q-01 (🔴) / D-3 (🟡), mcp Q-14 (🟡) / D-2 (🟢) → единый 🔴.
- `mcp-server/requirements.md` RF-11 — см. вопрос 17 (🟢 → 🟡); NFR «`--scale mcp-server=2`» (🟢) подтверждён только `application.yml:11` и комментарием compose → 🟡; «четыре предметных + get_asset» противоречит пяти tools (`search_assets`, `get_asset`, `explain_provenance`, `trace_dependencies`, `find_runtime_footprint`) + `ping`.
- `graph-query-core`/`mcp-server` trace — «≤ 500 узлов» (🟢): для `ResultKind.PATHS` действует только `maxPaths` (`ResultKind.java:11-13`) → 🟡; ссылка `ResultBudget.java:13` → `:14`; gqc Q-09 «Javadoc молчит» неверно (`SearchAssetsTool.java:48-50`, `AssetTemplates.java:48`).
- `docker/requirements.md:63` — «раздельные учётки Neo4j reader/writer» (🟢) — в Community это лишь отдельные credentials (`neo4j/init-reader.sh:3`) → 🟡; `docker/contracts.md:26` — nginx не ограничивает метод для `/webhooks/*` (405 отдаёт адаптер, `WebhookServer.java:99-100`); `keycloak-oidc/requirements.md:15` — `redirectUris` содержат wildcard (`archrag-realm.json:87`); тег `postgres:16` — 🔴 в docker, 🟡 в integration-tests → единый 🔴.
- `source-stubs` — AC-13/AC-09 и Q-01/Q-02 (🔴): дефекты подтверждены (`StubSource.java:138-140`, `StubSourceServer.java:113-120`, `EamApiStore.java:73-79`) → 🟢 факт; `eam-api-stub/requirements.md:43` «Compatibilidade — не подтверждено» противоречит D-10 и `docs/06-eam-integration.md:92-96`; `stub-source-journal/requirements.md:41` «все методы `synchronized`» неверно для `StubSource.system()` (`:48-50`).
- `demo-launcher` — штатная остановка даёт код 0 (🟡, `DemoLauncher.java:162-167`): shutdown hook + `await` даёт 143 на SIGTERM (воспроизведено на JDK 21 отдельным прогоном, в контейнере не проверялось) → 🔴; Q-04 — URL с учётными данными попадает в stderr через сообщение `URISyntaxException` (`LauncherConfig.java:122-123,138,152`, `DemoLauncher.java:79-82`) → 🟢, нарушение ловушки 12; `requirements.md:47` «все недостающие имена одним исключением» (🟢) → 🟡 (`LauncherConfig.java:118-121,133-135`).
- `ingestion-service/requirements.md:32` — «RECEIVED/RETRYING идут в `EventProcessor`»: диспетчер подбирает также застрявшие `VALIDATED`/`NORMALIZED`/`RESOLVED` (`PostgresEventJournal.java:214-215`); Q-09 подтверждён кодом (`JournalDispatcher.java:63,89-91,99`, `AdminLock.java:77-85`, `PostgresEventJournal.java:167-170`, `CrosswalkService.java:62,74`) → 🟢 факты.
- `graph-projector/questions.md` Q-05(а) — маркер без `objectCount` недостижим через диспетчер/replay (`StoredEventReader.java:88-93`, `JournalDispatcher.java:150-154`, `ReplayService.java:80-81`); 0 достижим только прямым вызовом `EventProcessor` → 🔴 → 🟡 (вопрос 6 уточнён). Прочие Q-09, Q-13, Q-15, Q-16, Q-18 — факты подтверждены кодом (`GraphProjector.java:454,574,611-647,669-671`, `PendingRelations.java:86-106`) → 🟢 факт.
- `graph-projector` BR-09/BR-11, `process-event` PE-02 «без вызовов» — `process` вызывает `journal.find` (`EventProcessor.java:186-188`).
- `permissions.md:87` — «оператор может объединить `gid`»: crosswalk бросает `CrosswalkConflictException` при разных `gid` (`PostgresIdentityMapping.java:107-116`); `permissions.md:54` ссылка на `QueryTemplateRegistry.java` указывает не на поиск шаблона (`:48-50`).
- `admin-ui` — «HTML не интерпретируется» (🟢) основан на Javadoc (`GraphCanvas.tsx:6-9`), `dangerouslySetInnerHTML` в коде не найден → 🟡.
- `adapter-core/requirements.md:30,95-98` — «прямого теста нет» неверно: `PollerTest.java:74-90,156-167` (тест на fake-журнале; правило остаётся 🟡); `:37` правило 12 (исключения) 🟢 → 🟡 (cause несёт значение источника); RF-01 приписывает `AdapterConfig` проверки `RetryPolicy` (`RetryPolicy.java:41-47` vs `AdapterConfig.java:39-54`).
- `eam-adapter/requirements.md:65` (идемпотентность) 🟢 → 🟡; `:25` BR-4 — reconcile живёт в graph-projector; Q-3…Q-7 подтверждены кодом (`EamApiConnector.java:115-118,180-186`, `EamApiCursor.java:59-60`, `CompositeEamConnector.java:78-85,148-150`).
- `scm-adapter`/`asset-adapter`/`deploymap-adapter` — нет `contracts.md` и `decisions.md` (scm, asset), `decisions.md` (deploymap); `deploymap` Q-03 (🔴) — положительная ветка `create` покрыта 6 IT (`F1AcceptanceIT:160` и др.) → 🟡; Q-02 «только в тесте» → «не вызывается из прод-кода»; `scm/requirements.md:55` смешивает `config` и `create`; Q-1 scm/deploymap/asset «кто валидирует `journal`/`rawStore`» — факт: никто (ленивый NPE, `InboxWriter.java:37-38`) → 🟢.
- `event-journal` — R-1…R-4 (🔴) содержат подтверждённые фактические части (`PEJ:140,214-215,345`, `EventProcessor.java:99-105`) → факт 🟢, намерение 🔴; `requirements.md:129` «R-1..R-4» неполно (в `questions.md` семь вопросов); правило 12 («значения не в исключениях», 🟢) нарушено в `S3RawPayloadStore.java:86` (`ref.key()` содержит `source`) → сузить.
- `normalizer` — NFR «потокобезопасность» (🟡) можно 🟢 для мапперов (`Normalizer.java:30-36,75`); Q-16 (`source-spi` в compile-scope без использования в `src/main`) → 🟢 факт.
- `event-schemas` — «конечные» статусы понимаются тремя способами: по автомату (3), по Javadoc (4), `FINISHED` в `EventProcessor.java:99-105` (5); Q-03 («5+ мест») против R-01 («4 места»); `source-version-compare` ведущие нули (🟡 → 🟢, `SourceVersion.java:13,20`); R-05 ссылается на `modules.json` вместо кода (`SourceChange.java:27` vs `SourceVersion.java:13`); `design.md:82` ссылка `GraphProjector.java:697-698` не подтверждает регистр `source` (🟡).
- `source-spi` — `Completeness.PARTIAL` «🟢/🟡» (только Javadoc, `Completeness.java:3-6`).
- `architecture.md:50`, `dependencies.md:97` — «contracts/* не зависит от остальных 🟢» с оговоркой «кроме друг друга»; фактически зависимость `event-schemas`/`source-spi` → `canonical-model` не используется (вопрос 10).

### Косметические
- Нумерация AC расходится: `event-journal/tasks.md:31,35,39,47` (A1..A4 → A1..A6, T1..T7 → T1..T8, R1..R6 → R1..R7, S1..S4 → S1..S5); `normalizer` — сквозные AC-01..AC-17 против AC-EAM/DM/RR/NE-xx; ссылки на `questions.md` normalizer (`requirements.md:39,142`, `design.md:148`: Q-02 → Q-06, Q-05/Q-06 → Q-08/Q-09); `event-journal/transition-event/requirements.md:106` «5 методов» → 4.
- `deploymap-adapter` RF-08/TT-02: «5 тестов» vs «6» в трассировке (5 контрактных + 1 привязка).
- Дрейф номеров строк: `eam design.md:12` (`AdapterConfig.java:59-62` → `60-63`), `scm requirements.md:15`, `design.md:28` (`EventMapper.java:46-48` → `47-49`), `asset design.md:10` (`34-54` → `39-54`), `normalizer` `DeploymentLookup.java:16-70` → `16-71`, `pom.xml:164-171` vs `161-173` (корневой pom), `HttpSourceConnector.java:37` → `37-38`.
- Единый формат идентификаторов вопросов: `Q-1`/`Q-01`/`R-01`/`1.` (asset).
- `event-schemas` без `decisions.md` (решения в `design.md:89-99`); в подпапках use case нет `contracts/decisions/questions` — зафиксировать соглашение.
- `code-spec-matrix.md`: 39 файлов `n/a` (20 в `eam-adapter`, 46% покрытия) — это не нарушение, но по ним нет покрытия.
- `openapi/mcp-server.yaml`: описан только `POST /mcp`; GET/DELETE streamable-транспорта не заведены (не проверялось, возможно намеренно).

## 4. Проверки, прошедшие чисто
- ~2100 относительных ссылок в 410 `.md` — битых нет (10 якорей включены).
- 9448 цитат `path:line` — файл существует и строка в пределах для каждой; точность попадания проверена выборочно (~300 цитат 🟢, расхождения — в п. 3).
- `code-spec-matrix.md`: 549 строк = 550 файлов репозитория за вычетом `docs/`, `.reversa/`, `_reversa_sdd/` и `package-lock.json` (исключён самой матрицей).
- Слои `CLAUDE.md` в `pom.xml` соблюдены (`access/*` не зависит от `ingestion/*` и `adapters/*`), кроме неиспользуемой зависимости `contracts/*` → `canonical-model` (вопрос 10).
- Все 13 `@*Mapping` контроллеров есть в `openapi/*.yaml`; лишних путей нет.
- `index.md` перечисляет все 21 каталога единиц и служебные каталоги; все 20 модулей `surface.json` имеют единицу.

## 5. Решения владельца (2026-10-13) и последствия для specs

Все 20 вопросов `questions.md` отвечены. Решения 1–6, 10, 11, 13, 15–18, 20 задают **целевое поведение, отличное от кода**: specs продолжают описывать код «как есть», расхождение фиксируется как подтверждённый разрыв «решение владельца ↔ код» и требует задач реализации (предложены архитектору).

| Вопрос | Решение | Разрыв с кодом / что нужно сделать | Тип |
|---|---|---|---|
| 1 | Закрывать `SyncRun`, глубина хранения — параметр конфигурации | `GraphProjector.java:695-700` не закрывает; нет retention | задача кода |
| 2 | Писать `replayed_at`, выход из `QUARANTINED`, лимит `RETRYING`, `SUPERSEDED` — привязать или удалить | см. R-1…R-4 `event-journal`, Р-1…Р-3 | задача кода + анализ `SUPERSEDED` |
| 3 | Перепроекция после `approve` | `PostgresIdentityMapping.java:80-145` трогает только crosswalk | задача кода |
| 4 | Реализация по ADR 0020 | узел закрывается по любым активным assertions; HWM после коммита Neo4j | задача кода (при необходимости отдельная) |
| 5 | Удалить `CloseAssertion` | `canonical-model`, `graph-projector`, `normalizer` | задача кода; поправить `docs/06:82` |
| 6 | Защита нужна | `Reconciler`: маркер без `objectCount`, `PARTIAL`; после п. 15 | задача кода |
| 7 | Истина — код | правка docs/ADR | задача архитектора (docs) |
| 8 | Loopback — свойство стенда | дополнить ADR 0026 | задача архитектора (ADR) |
| 9 | Анализ дублей и предложение | `spec-impact-matrix.md` §2 | задача анализа (без кода) |
| 10 | Зависимость `contracts/*` → `canonical-model` — штатная | уточнить правило в `CLAUDE.md`; «починить, что сломается» — по факту сборки сломаться нечему (зависимость уже есть) | уточнение `CLAUDE.md` |
| 11 | Все API с авторизацией: admin — управление/контроль, reader — MCP | ~~`POST /control/snapshot` без аутентификации; MCP: scope только для `tools/call`; роль не проверяется~~ — реализовано в UNLOCKER-290: роли `archrag-console-admin` / `archrag-reader` проверяются во всех API | выполнено (UNLOCKER-290) |
| 12 | Одна машина, скромные лимиты | NFR PoC: SLO/RPO-RTO не вводятся, задать лимиты контейнеров | ADR 0022 |
| 13 | Источник deploy map — позже; проверка подключения EAM добавить | `eam-adapter` Q-1 | задача кода |
| 14 | Пробелы тестов зафиксировать, не закрывать до приёмки | `integration-tests` Q-01…Q-35 остаются в specs | — |
| 15, 18 | Дефекты | `Reconciler` без `forget`; `FindRuntimeFootprintTool` без нормализации `systemGid` | задачи кода |
| 16 | Принять `localStorage` | ADR 0026:21 исправить; комментарий `authService.ts:36-37` | задача архитектора + мелкая правка |
| 17 | Аудит нужен | отказы `ToolScopeFilter.java:60-92` без аудита | задача кода |
| 19 | Эталон — MCP | UI `AssetCard.tsx:9-13` привести к семантике `TraceDependenciesTool.java:187-190,228` | задача кода |
| 20 | Нужны `MANUAL` и тест согласованности | `SyncSources.CODES`, `EamApiControl.java:27` | задача кода |

Specs не меняются под целевое поведение до реализации (иначе они перестанут быть подтверждёнными кодом). R.8 (UNLOCKER-274) переклассифицировал метки 🔴 по ответам: вопросы-намерения → 🟢 `[R.8: ответ владельца, вопрос N]`, решения с кодом → 🟡 `[R.8: решение принято, реализация — UNLOCKER-276 (п. N)]`. Охват — вхождения, привязанные к вопросам по идентификаторам `Q-NN`/`R-N` внутри единицы и реестру `Р-n`/`П-n`; прочие 🔴 (не связанные с 20 вопросами) оставлены. Правки docs/ADR и кода — UNLOCKER-276.
