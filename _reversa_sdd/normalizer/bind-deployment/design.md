# bind-deployment — дизайн

> Общий дизайн: [`../design.md`](../design.md). Номера строк — `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/DeployMapMapper.java`, если не указано иное.

## Interface
`DeployMapMapper implements CanonicalMapper`: конструкторы `()` (= `DeploymentLookup.NONE`) и `(DeploymentLookup lookup)` с `requireNonNull` (`:61-67`); `source() = DEPLOYMAP` (`:70-72`), `supportedSchemaVersions() = {"1"}` (`:75-77`), `map` (`:80-136`). Константы: `ENVIRONMENT`, `DEPLOYMENT`, `FORMAT_V0 = "deploymap-poc-0"`, `FORMAT_V1 = "deploymap-poc-1"`, `CHART_META = "chartMeta"`, `ANNOTATION_EAM_SYSTEM_ID = "archrag.io/eam-system-id"`, `ANNOTATION_GIT_REPO_URL = "archrag.io/git-repo-url"`, `ANNOTATION_GIT_COMMIT = "archrag.io/git-commit"` (`:46-54`); `COMMIT = [0-9a-f]{40}`, `DIGITS = [0-9]+` (`:56-57`).

## Основной поток `map` (`:80-136`)
`ENVIRONMENT` (`:84-91`): `code = key.sourceId()`; `classText = optional("class") ?? code`; `UpsertNode(Environment(code, required name, environmentClass(classText)))`.

`DEPLOYMENT` (`:92-133`):
1. `chart = chartMeta(p)` — `null` (poc-0/без format) либо `ChartMeta(eamSystemId, repositoryUrl, commit)` (`:93`, `:142-163`).
2. `UpsertNode(Deployment(sourceId, required name, optional chartVersion, optional status, chart?.commit, chart?.repositoryUrl, chart?.eamSystemId, input.time()))` (`:94-105`).
3. `chart == null` → если задан `service`, `payloadService(...)` (`:106-108`); иначе `bindToRepository(chart, p, key, validity(p), sink)` (`:109-111`).
4. `environment` → `relation(IN_ENVIRONMENT, key, DEPLOYMENT, (DEPLOYMAP,ENVIRONMENT,env), ENVIRONMENT, validity, "environment")` (`:112-122`).
5. Для каждого `hosts[i]` → `relation(RUNS_ON, key, DEPLOYMENT, (CMDB,COMPUTE_INSTANCE,host), COMPUTE_INSTANCE, validity, "hosts")` (`:123-132`).
6. иной `sourceType` → `UNKNOWN_SOURCE_TYPE "unknown deploymap sourceType"` (`:134`).

### `chartMeta(p)` (`:142-163`)
1. `format = optional("format")`; не `null` и не `FORMAT_V0`/`FORMAT_V1` → `INVALID_PAYLOAD "unknown deploymap format"` (`:143-146`).
2. `format != FORMAT_V1` → `return null` (`:147-149`).
3. `meta = optionalObject("chartMeta")`; пусто → `MISSING_REQUIRED_FIELD "required field is missing: chartMeta"` (`:150-153`).
4. `annotations = meta.optionalObject("annotations") ?? пустой` (`:154`); `required` для трёх аннотаций в порядке: `eam-system-id`, `git-repo-url`, `git-commit` (`:155-157`).
5. `COMMIT.matcher(commit).matches()` иначе `invalidAnnotation(git-commit)` (`:159-161`).
6. `new ChartMeta(systemId(systemId), normalizedUrl(repoUrl), commit)` — порядок проверки значений: commit → системный id → URL (`:162`, но в выражении `systemId(...)` вычисляется раньше `normalizedUrl(...)`).
   - `systemId` (`:166-178`): `DIGITS.matches` и `Long.parseLong` > 0 → значение; `NumberFormatException` → `invalidAnnotation(eam-system-id)`; иначе то же.
   - `normalizedUrl` (`:180-186`): `GitUrls.normalize`, `IllegalArgumentException` → `invalidAnnotation(git-repo-url)`.

### `bindToRepository` (`:193-225`) — только poc-1
1. `repository = lookup.repositoryByUrl(chart.repositoryUrl())`.
2. `target = repository ?? LookupKeys.repositoryUrl(url)`; `relation(DEPLOYED_FROM, key, DEPLOYMENT, target, REPOSITORY, Map.of("commit", chart.commit()), validity, "git-repo-url")` (`:196-207`).
3. `declared = optional("service")→(SCM,SERVICE,service)`; `services = repository.map(lookup::servicesImplementedIn) ?? []` (`:209-211`).
4. Ветки:
   - `declared.isPresent() && services.contains(declared)` → `relation(HAS_DEPLOYMENT, declared, SERVICE, key, DEPLOYMENT, validity, "service")` (`:212-214`);
   - `services.size() == 1` → `relation(HAS_DEPLOYMENT, services.get(0), …, "git-repo-url")` (`:215-218`);
   - `declared.isPresent()` → `payloadService(declared.sourceId(), key, validity, sink)` (`:219-220`);
   - иначе `sink.warn("DEPLOYMENT_SERVICE_UNRESOLVED")` (`:221-224`).

`payloadService` (`:228-237`): `relation(HAS_DEPLOYMENT, (SCM,SERVICE,service), SERVICE, key, DEPLOYMENT, validity, "service")`. Конкретно при неизвестном сервисе отложится через `CommandSink`.

`environmentClass` (`:244-252`), `validity` (`:240-242`).

## Данные (таблица mapping Deployment)
| Поле `Deployment` | Источник |
|---|---|
| `deploymentKey` | `key.sourceId()` |
| `name` | `payload.name` (обязателен) |
| `version` | `payload.chartVersion` |
| `status` | `payload.status` |
| `sourceCommit` | `annotations["archrag.io/git-commit"]` (poc-1) |
| `repositoryUrl` | `GitUrls.normalize(annotations["archrag.io/git-repo-url"])` (poc-1); модель требует каноничность (`CM/node/Deployment.java:30-32`) |
| `eamSystemId` | положительный `long` из `annotations["archrag.io/eam-system-id"]` |
| `observedAt` | `event.time()` |

## Зависимости
`DeploymentLookup.repositoryByUrl`, `servicesImplementedIn` (реализует `GraphProjector`); `GitUrls`, `LookupKeys`, `Deployment`, `Environment`, `EnvironmentClass`; `ScmMapper.SERVICE`, `CmdbMapper.COMPUTE_INSTANCE` (константы) (`:128,210,231`). Форматы генерирует `platform/source-stubs` (`DeployMapFormat`, `StubSources`); контракт fixtures — `adapters/deploymap-adapter` (`DeployMapFixturesContractTest`).

## Решения
| Решение | Evidence | Уверенность |
|---|---|---|
| Сервис выбирается по репозиторию, не по строке `service` | ADR 0029 п. 3; `:209-224` | 🟢 |
| Неизвестный репозиторий — отложенная связь, не карантин | ADR 0029 п. 7; `:195-198` | 🟢 |
| Система chart не сравнивается в маппере | ADR 0029 п. 4; ADR 0030; `NT/DeployMapMapperTest#systemMismatchGivesOrdinaryBindingWithoutConflictOrWarning` | 🟢 |
| `DEPLOYED_FROM` несёт `commit` как identity-свойство | `CM/relation/RelationType.java:20`; `:205` | 🟢 |
| Формат временный и будет заменён | `:23-26`; `docs/03-interfaces.md` («Временный формат») | 🟢 |

## Связи с другими спецификациями
Матрица влияния №16 (формат `deploymap-poc-0/1`, генерация в `source-stubs`, разбор здесь, защита — `DeployMapFixturesContractTest` в `adapters/deploymap-adapter`), №11 (нормализация URL: `GitUrls` против `FeatureNormalizer`), №15 (`deploymap-adapter` ↔ `DEPLOYMAP`/`deploymap`). `EventProcessor.java:443-470` использует `eamSystemOfService`/`chartDeploymentsOfService` для проверки системы и переоценки привязки — вне модуля. См. [`../design.md`](../design.md) §6.

## Риски
- 🟡 Q-09: Javadoc класса называет поле `chart`, которое код не читает (`:24-26` против `:92-133`).
- 🟡 Q-15: снимок привязки без переоценки.
- 🟡 Q-10: две нормализации URL.
- 🟡 Если `lookup.repositoryByUrl` вернёт ключ, а `exists` ложно — связь `DEPLOYED_FROM` всё равно уйдёт в `unresolved` (общее поведение `CommandSink.relation`).
