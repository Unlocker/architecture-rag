# Карта решений: Git и код → реестр ADR

> Detective, этап R.4 (UNLOCKER-269). Это **не новые ADR**: реестр `docs/adr/0001–0031` уже есть, он считается существующим (в задаче указан диапазон 0001–0028, но в `epic/UNLOCKER-260` уже лежат 0029–0031). Для каждого решения, найденного в истории Git (`git log --no-merges`, 217 коммитов) или в коде, здесь дана ссылка на ADR; пересказа нет. Решения без ADR вынесены в [`candidates.md`](candidates.md). Расхождения кода с ADR — в [`../detective-gaps.md`](../detective-gaps.md). Шкала: 🟢 решение есть в ADR и код ему соответствует · 🟡 ADR есть, но код делает больше/иначе (уточнение) · 🔴 код противоречит ADR или `docs/`.

## 1. Решения, уже закреплённые ADR

| Что видно в Git/коде | Коммиты / место | ADR | Оценка |
|---|---|---|---|
| Neo4j — производная read-only витрина | `docs/00-vision.md`; весь `access/*` читает через `executeRead` | [0001](../../docs/adr/0001-neo4j-derived-read-model.md) | 🟢 |
| Четыре контура (`contracts/adapters/ingestion/access`) | `7064bf2` E0.1, структура модулей | [0002](../../docs/adr/0002-four-contours.md) | 🟢 |
| `gid` + `SourceRecord {source, sourceType, sourceId}` | `348fd79` UNLOCKER-160; `0a4e6ea` UNLOCKER-173 | [0003](../../docs/adr/0003-gid-and-source-record-identity.md) | 🟢 |
| Составной UNIQUE вместо NODE KEY, без RBAC | `67f213f` UNLOCKER-162 (`Neo4jSchema`) | [0004](../../docs/adr/0004-neo4j-community-constraints.md) | 🟢 |
| Read-only MCP на уровне приложения | `a4bbcaf` UNLOCKER-179; `59c4fcb` UNLOCKER-197 (reader) | [0005](../../docs/adr/0005-readonly-mcp-by-application.md) | 🟢; сама учётка `archrag_reader` не ограничена в СУБД — ровно то, что фиксирует ADR |
| PostgreSQL inbox как event backbone | `d45311d` UNLOCKER-164 | [0006](../../docs/adr/0006-postgres-inbox-event-backbone.md) | 🟢 |
| Raw в S3; SeaweedFS вместо MinIO | `597a1e3` (MinIO, закреплён digest), затем `f9383e2` UNLOCKER-202 (MinIO → SeaweedFS), `763fc3c` | [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md) | 🟢 |
| Authority matrix на уровне полей и связей | `971887c` UNLOCKER-161 | [0008](../../docs/adr/0008-authority-matrix-field-level.md) | 🟡 мастер `DECOMPOSED_INTO` — SCM (`ae61738`, UNLOCKER-213) в ADR 0008 не назван (там только `DEPENDS_ON` — EAM); решение живёт в `AuthorityMatrix` и `docs/` |
| `Deployment` из deploy map | `f7213de` UNLOCKER-166 | [0009](../../docs/adr/0009-deployment-from-deploymap.md) | 🟢, уточнён 0029 |
| Мастер-системы — заглушки | `778bdd7`, `f267fef` UNLOCKER-165 | [0010](../../docs/adr/0010-master-systems-are-stubs.md) | 🟢 |
| `Namespace`/`KubernetesCluster` не наполняются | `AuthorityMatrix` (мастер DEPLOYMAP есть, мапперов нет) | [0011](../../docs/adr/0011-namespace-cluster-modeled-not-populated.md) | 🟢 |
| `analyze_impact` = `trace_dependencies(mode=impact)` | `c6b2072`/`4a3610d` UNLOCKER-189/190 | [0012](../../docs/adr/0012-trace-dependencies-impact-mode.md) | 🟢 |
| Операции оператора — админский эндпоинт | `ab26e65` UNLOCKER-170 | [0013](../../docs/adr/0013-operator-ops-via-admin-endpoint.md) | 🟡 механика reconcile (`9656ce5`, `14a6e1f`, `24a0b0b`) в ADR не описана — К-5 |
| SLA webhook → Neo4j ≤ 30 с | `8f90e55` UNLOCKER-171; `3225038` UNLOCKER-226 | [0014](../../docs/adr/0014-sla-webhook-to-neo4j.md) | 🟢 |
| Java 21, Spring Boot 4.x, Spring AI 2.x, Maven | `7064bf2`, `4b513d4` UNLOCKER-156 | [0015](../../docs/adr/0015-java21-spring-maven-stack.md) | 🟢 |
| Координаты `archrag` вместо `akp` | `52b4820` UNLOCKER-201 | [0016](../../docs/adr/0016-archrag-coordinates.md) | 🟢 |
| MCP — Streamable HTTP в режиме `STATELESS` | `78ed9b3` UNLOCKER-177; уточнение `fd2fe4f` UNLOCKER-224 | [0017](../../docs/adr/0017-mcp-streamable-http.md) | 🟢 (`spring.ai.mcp.server.protocol=STATELESS`, `access/mcp-server/src/main/resources/application.yml:11`) |
| Scope на каждый tool, fail-closed | `d1de718` UNLOCKER-178; `fc2f963` | [0017](../../docs/adr/0017-mcp-streamable-http.md), п. «Ограничения» | 🟢 |
| Предметные tools, не произвольный Cypher | `5ab7d29`, `bb23f29`, `a230513`, `0707d21` | [0018](../../docs/adr/0018-domain-tools-not-arbitrary-cypher.md) | 🟢 |
| Identity resolution без auto-merge; mapping и crosswalk в E1 | `0a4e6ea` UNLOCKER-173; `931e33d`, `526f0e6` UNLOCKER-174; `98407fa` UNLOCKER-203 | [0019](../../docs/adr/0019-identity-resolution-no-auto-merge.md) | 🟡 числовые параметры matching в ADR нет — К-6 |
| Tombstone вместо hard delete, только после `snapshot-complete` | `39262c9` UNLOCKER-168; `53474af` UNLOCKER-169 | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) | 🟡 см. 3.1 и 3.2 ниже |
| Демо-стенд на Compose; раздельные сети, Docker secrets | `b7f401d` UNLOCKER-196; `59c4fcb` UNLOCKER-197; `9140698` UNLOCKER-199 | [0021](../../docs/adr/0021-compose-demo-stand.md) | 🟢 |
| Вне PoC: semantic retrieval и др. | `docs/00-vision.md` | [0022](../../docs/adr/0022-out-of-poc-scope.md) | 🟢 |
| Ветвление `develop → epic → feature` | `docs/01-development-order.md` | [0023](../../docs/adr/0023-branching-process.md) | 🟢 |
| `sourceId` без префикса системы | `d96c699` UNLOCKER-247; `ScmMapper.java:60-64` | [0024](../../docs/adr/0024-open-source-id-format.md) | 🟢 |
| Индекс `deployment_env` удалён | `d96c699`; `Neo4jSchema` без этого индекса | [0025](../../docs/adr/0025-open-deployment-env-index.md) | 🟢 |
| Консоль — read-only, loopback; `azp` allow-list | `0f9579e`, `cef6def`, `ea33d7e` (azp), `8fd0e65`, `bf780a2` | [0026](../../docs/adr/0026-admin-console-readonly-loopback.md) | 🟢 |
| Фронтенд React + TypeScript + Webpack | `265754f` UNLOCKER-235; `0ea8ec2`, `63924fb` UNLOCKER-253 | [0027](../../docs/adr/0027-frontend-react-typescript-webpack.md) | 🟢 |
| Структурные связи темпоральны | `5b3ba6c` UNLOCKER-217; `38b5d79` UNLOCKER-218 | [0028](../../docs/adr/0028-structural-relations-temporal.md) | 🟡 механика замещения (UNLOCKER-217) только упомянута — К-4 |
| Helm chart: система, репозиторий, commit; `DEPLOYED_FROM {commit}` | `2ba0b18`, `8fd4ae1` UNLOCKER-250; `0864fc7` UNLOCKER-252 | [0029](../../docs/adr/0029-helm-chart-system-repo-commit.md) | 🟢 |
| Конфликт «система EAM из chart ↔ система сервиса» | `ab61b2c` UNLOCKER-254 | [0030](../../docs/adr/0030-deployment-system-conflict.md) | 🟢 |
| Инвариант платформы ИТ-системы | `bc4043d`, `5e2e349`, `3e90c3e` UNLOCKER-259 | [0031](../../docs/adr/0031-it-system-platform-invariant.md) | 🟢 |

## 2. Развилки и откаты в истории (reverts, переделки)

| Что произошло | Коммиты | Чем закончилось | Покрыто |
|---|---|---|---|
| Сначала `eamPlatformId` как отдельное свойство узла и колонка `platform_id` в `source_conflict`, затем откат, затем явные правила authority для `platformId` и порт `EamLookup` | `884c57f` → `a211d51` (Revert) → `b5293ef`, `5e2e349`, `3e90c3e` | свойство `platformId`, строка в общей `source_conflict` с `kind=RELATION` | ADR [0031](../../docs/adr/0031-it-system-platform-invariant.md) 🟢 (причина отката не записана в коммите; в ADR — «по плану архитектора») 🟡 |
| `validTo` на `HOSTED_ON` добавили и убрали | `0707d21` → `95594a6` | `HOSTED_ON` и `PART_OF` остаются нетемпоральными (`RelationType.java:21-22`) | частично: ADR 0028 говорит про четыре типа, но не про то, что остальные не темпоральны намеренно — К-4 🟡 |
| Hard-failure CI «доказать, что красный бывает» | `2139cff` → `537e815` | служебная проверка CI | не архитектурное |
| MinIO → SeaweedFS | `597a1e3` → `f9383e2` | ADR 0007 | 🟢 |
| `deployment_env` сначала индекс, затем связь `IN_ENVIRONMENT` | `67f213f`; `d96c699` | ADR 0025 | 🟢 |
| Admin REST принимал любой токен со scope → добавили `azp` | `ea96fbc` (UNLOCKER-229) → `ea33d7e` (UNLOCKER-239) | ADR 0026 | 🟢 |
| Reconcile сначала «повторное применение missing set», затем «новый snapshot адаптера» | `14a6e1f` → `9656ce5` UNLOCKER-170 | `POST /admin/reconcile/{source}` проксирует `/control/snapshot` | К-5 |
| Критичность EAM: `WARN` → `DEBUG` + счётчик | `3b5b80e` UNLOCKER-256 | неизвестное значение не отбрасывается, а сохраняется сырым | `docs/06-eam-integration.md`; К-10 |

## 3. Где код делает больше или иначе, чем ADR (🟡/🔴)

### 3.1. ADR 0020 и «другие активные assertions»

ADR: «канонический узел закрывается только при отсутствии других активных assertions». Код: узел архивируется, только если нет других активных **MASTER**-утверждений; активное SUPPLEMENTARY-утверждение узел не удерживает (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:543-548`). Это сужение, а не противоречие; нужно уточнение формулировки при следующем касании ADR 0020 🟡.

### 3.2. ADR 0020 и high-water mark

ADR: «High-water mark двигается после успешной проекции страницы». Код: курсор poller'а адаптера сдвигается **после `append` страницы в журнал** (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java:22-25, 136`), а после коммита Neo4j сдвигается отдельный checkpoint `projector` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:612-617`). Это два разных «water mark», разделённых журналом — осознанное следствие ADR 0006, но формулировка 0020 их не различает 🟡.

### 3.3. `docs/02-data-model.md` и состояния

`docs/02-data-model.md:241-273` и `docs/00-vision.md:383-386` описывают `PROJECTED → SUPERSEDED` и выход `QUARANTINED → RETRYING` «решением оператора»; в коде оба перехода разрешены в `canTransitionTo`, но **никем не выполняются** ([`../state-machines.md`](../state-machines.md), §1.5) 🔴. `docs/02-data-model.md:273` называет `UNRESOLVED_ENDPOINT` типичной причиной `RETRYING` («конец связи ещё не пришёл»); после E1.14 штатный случай — `DeferRelation` без повтора, а `UNRESOLVED_ENDPOINT` остаётся для связи, чей конец известен нормализатору, но не имеет `gid` (`EventProcessor.java:221-223, 589-597`) 🟡.

### 3.4. Brief агента и ADR 0017

Бриф разработчика называет `spring.ai.mcp.server.protocol=STREAMABLE`; в репозитории `STATELESS` по ADR 0017. Расхождения кода с ADR нет; расхождение — между брифом агента и ADR (решает архитектор) 🟡.
