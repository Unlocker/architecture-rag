# bind-deployment — Маппинг deploy map и привязка Deployment к репозиторию и сервису

> Use case модуля [`../`](../requirements.md). `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `.../src/test/java/.../normalizer/`. Формат временный (ADR 0009/0029) — см. Q-09.

## Обзор
`DeployMapMapper` разбирает источник `urn:corp:deploymap`: `ENVIRONMENT` и `DEPLOYMENT`. Для формата `deploymap-poc-1` развёртывание несёт метаданные chart (`chartMeta.annotations`): система EAM, репозиторий и commit; нормализатор привязывает `Deployment` к репозиторию (`DEPLOYED_FROM{commit}`) и выбирает сервис для `HAS_DEPLOYMENT` по репозиторию 🟢 (`NZ/DeployMapMapper.java:22-44`).

## Бизнес-правила
- BR-DM-01. `ENVIRONMENT`: ключ окружения (`Environment.code`) = `sourceId`; `name` обязателен; класс = `class` или, если его нет, `sourceId`, затем словарь: `dev|development`→`DEV`, `test|qa`→`TEST`, `preprod|stage|staging`→`PREPROD`, `prod|production`→`PROD` (регистр не важен); иное → `INVALID_PAYLOAD "cannot determine environment class"` 🟢 (`:84-91,244-252`).
- BR-DM-02. `DEPLOYMENT`: `Deployment(deploymentKey=sourceId, name*, version=chartVersion, status, sourceCommit, repositoryUrl, eamSystemId, observedAt=event.time())`; `name` обязателен 🟢 (`:94-105`).
- BR-DM-03. Формат: поле `format` — `deploymap-poc-0` или `deploymap-poc-1` либо отсутствует; иное значение → `INVALID_PAYLOAD "unknown deploymap format"`; `poc-0` и отсутствие `format` разбираются «по-старому» (метаданные chart игнорируются) 🟢 (`:143-149`, `NT/DeployMapMapperTest#pocZeroKeepsLegacyBehaviourAndIgnoresChartMeta`, `#recordWithoutFormatOrChartMetaIsLegacy`). Событийная `schemaVersion` остаётся `1` (`:75-77`).
- BR-DM-04. `poc-1`: `chartMeta` (объект) обязателен (иначе `MISSING_REQUIRED_FIELD "required field is missing: chartMeta"`; не объект → `INVALID_PAYLOAD`); `chartMeta.annotations` — объект, отсутствие = пустой; обязательны **все три** строки `archrag.io/eam-system-id`, `archrag.io/git-repo-url`, `archrag.io/git-commit` (иначе `MISSING_REQUIRED_FIELD` с именем аннотации) 🟢 (`:150-157`).
- BR-DM-05. Валидация значений аннотаций (причина называет аннотацию, не значение): `git-commit` ∈ `[0-9a-f]{40}` (верхний регистр, 39/41 символ — ошибка); `eam-system-id` — только цифры `[0-9]+`, положительное, влезает в `long` (`+5101`, `0`, `EAM-1042`, `99999999999999999999` — ошибка); `git-repo-url` → `GitUrls.normalize`, ошибка → `INVALID_PAYLOAD "annotation has invalid format: archrag.io/git-repo-url"`; общий текст `annotation has invalid format: <имя аннотации>` 🟢 (`:159-190`, `NT/DeployMapMapperTest#invalidAnnotationValuesAreQuarantinedWithoutLeakingValues`).
- BR-DM-06. `poc-1`, привязка к репозиторию: `DEPLOYED_FROM` Deployment→репозиторий с идентичным свойством `{commit}`; цель — реальный ключ из `lookup.repositoryByUrl(normalizedUrl)` либо alias `LookupKeys.repositoryUrl(url)`; `field = "git-repo-url"` 🟢 (`:193-207`).
- BR-DM-07. Выбор сервиса для `HAS_DEPLOYMENT` (`poc-1`): `declared = payload.service` (если задан), `services = repository ? lookup.servicesImplementedIn(repository) : []`:
  1. `declared ∈ services` → связь от `declared` (`field="service"`);
  2. иначе `services.size() == 1` → связь от единственного (`field="git-repo-url"`), даже если payload указал другой или не указал;
  3. иначе `declared` есть → «старая семантика»: связь от `(SCM,SERVICE,declared)`; если сервис неизвестен — отложенная (`field="service"`);
  4. иначе (нет `declared`; ноль или несколько сервисов) → связи нет, `warn("DEPLOYMENT_SERVICE_UNRESOLVED")`.
  Снимок на момент нормализации; переоценки нет 🟢 (`:209-224`, Javadoc `:38-42`).
- BR-DM-08. `poc-0`/без `format`: если есть `service` — `HAS_DEPLOYMENT` от `(SCM,SERVICE,service)` (`payloadService`); `DEPLOYED_FROM` нет; `sourceCommit/repositoryUrl/eamSystemId = null` 🟢 (`:106-108`).
- BR-DM-09. Общие связи для обоих форматов: `environment` → `IN_ENVIRONMENT` на `(DEPLOYMAP,ENVIRONMENT,env)`; каждый элемент `hosts[]` → `RUNS_ON` на `(CMDB,COMPUTE_INSTANCE,host)`; `validity` = `Validity(validFrom,null)` из payload или `null` 🟢 (`:112-132,240-242`).
- BR-DM-10. Нормализатор не сравнивает систему chart с системой сервиса (`eamSystemId` ≠ системе сервиса → обычная привязка без warning); конфликт открывает `EventProcessor` (ADR 0030) 🟢 (`NT/DeployMapMapperTest#systemMismatchGivesOrdinaryBindingWithoutConflictOrWarning`).
- BR-DM-11. Порядок ошибок в `DEPLOYMENT`: сначала `chartMeta` (формат, аннотации), затем `name`, затем остальные поля 🟢 (`:93` до `:99`).

## Функциональные требования
| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-DM-01 | `ENVIRONMENT` → `Environment(code,name,class)` | Must | AC-DM-01 |
| RF-DM-02 | `DEPLOYMENT` poc-1 → `Deployment` с commit/URL/системой, `DEPLOYED_FROM{commit}`, `HAS_DEPLOYMENT` | Must | AC-DM-02 |
| RF-DM-03 | Карантин на отсутствующие/неверные аннотации и неизвестный `format` без значений в причине | Must | AC-DM-03, AC-DM-04 |
| RF-DM-04 | Выбор сервиса по приоритетам 1…4 | Must | AC-DM-05…AC-DM-08 |
| RF-DM-05 | Неизвестный репозиторий — не карантин, а отложенная `DEPLOYED_FROM` на alias | Must | AC-DM-09 |
| RF-DM-06 | `poc-0`/без `format` — legacy-поведение | Could | AC-DM-10 |
| RF-DM-07 | `IN_ENVIRONMENT`, `RUNS_ON`, `validFrom` | Must | AC-DM-11 |

## НФТ
| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Значения аннотаций (commit, URL с учётными данными, id) не попадают в причины карантина | `NZ/DeployMapMapper.java:158,188-190`; `NT/DeployMapMapperTest#invalidAnnotationValuesAreQuarantinedWithoutLeakingValues` | 🟢 |

## Критерии приёмки
```gherkin
# AC-DM-01 — NormalizerTest#deployMapEnvironmentInfersClassFromCode
Дано ENVIRONMENT prod {name: "Production"} без class  Тогда Environment("prod","Production",PROD)
Дано ENVIRONMENT без name  Тогда MISSING_REQUIRED_FIELD (#absentNameOfEnvironmentAndDeploymentIsQuarantinedNotDefaulted)

# AC-DM-02 — DeployMapMapperTest#pocOneMapsCommitUrlSystemAndBindsRepositoryAndService
Дано poc-1 с корректными аннотациями (eam-system-id 5101, git-repo-url ssh-формы, commit 40 hex), репозиторий известен и реализуется сервисом svc-payments-api
Тогда Deployment("dep-1","payments-api","1.4.2",null,COMMIT,URL,5101L,eventTime); DEPLOYED_FROM Deployment→репозиторий со свойством commit; HAS_DEPLOYMENT от svc-payments-api; unresolved и warnings пусты

# AC-DM-03 негатив — #missingAnnotationIsQuarantinedWithoutCommands, #pocOneWithoutChartMetaIsQuarantined
Дано poc-1 без любой из трёх аннотаций (или значение "  ")  Тогда MISSING_REQUIRED_FIELD
Дано poc-1 без chartMeta или chartMeta без annotations  Тогда MISSING_REQUIRED_FIELD

# AC-DM-04 негатив — #invalidAnnotationValuesAreQuarantinedWithoutLeakingValues, #unknownFormatIsQuarantined, #wrongChartMetaTypeIsQuarantined
Дано commit в верхнем регистре / 39 символов / длиннее 40, id "EAM-1042"|"+5101"|"0"|"99999999999999999999", URL "ftp://…@host/x" или произвольная строка
Тогда INVALID_PAYLOAD; reason не содержит ни секрета, ни COMMIT, ни "EAM-1042"
Дано format "deploymap-poc-9" или chartMeta = строка  Тогда INVALID_PAYLOAD

# AC-DM-05 — #declaredServiceAmongRepositoryServicesWins
Дано репозиторий реализуется PAY и LEDGER, payload.service = svc-ledger-api  Тогда HAS_DEPLOYMENT от LEDGER, warnings пусты
# AC-DM-06 — #singleRepositoryServiceIsUsedEvenIfPayloadDisagreesOrIsMissing
Дано репозиторий реализуется только PAY, payload.service = svc-ledger-api или не задан  Тогда HAS_DEPLOYMENT от PAY
# AC-DM-07 — #severalServicesWithForeignDeclaredServiceFallBackToPayloadService, #repositoryWithoutServicesFallsBackToPayloadService
Дано несколько сервисов, payload.service не среди них (известен как svc-other) | сервисов нет  Тогда HAS_DEPLOYMENT от payload.service
# AC-DM-08 негатив — #severalServicesWithoutDeclaredOneAreNotGuessed, #unknownRepositoryWithoutServiceWarns
Дано несколько сервисов без payload.service  Тогда HAS_DEPLOYMENT нет, DEPLOYED_FROM есть, warnings = ["DEPLOYMENT_SERVICE_UNRESOLVED"]
Дано репозиторий неизвестен и service не задан  Тогда HAS_DEPLOYMENT нет, warnings = ["DEPLOYMENT_SERVICE_UNRESOLVED"]
# AC-DM-09 — #unknownRepositoryDefersDeployedFromOnAliasAndKeepsServiceFallback
Дано репозиторий неизвестен, PAY известен, payload.service = svc-payments-api
Тогда unresolved = одна запись DEPLOYED_FROM с target = alias (SCM,REPOSITORY_URL,url), relation.properties содержит commit; команды DEPLOYED_FROM нет; HAS_DEPLOYMENT от PAY есть
# AC-DM-10 — #pocZeroKeepsLegacyBehaviourAndIgnoresChartMeta, #recordWithoutFormatOrChartMetaIsLegacy
Дано format = deploymap-poc-0 (с chartMeta) или без format  Тогда Deployment без commit/URL/системы; DEPLOYED_FROM нет; HAS_DEPLOYMENT есть
# AC-DM-11 — NormalizerTest#deployMapDeploymentMapsServiceEnvironmentAndHosts, #structuralRelationsCarryValidityFromSource, #deploymentWithUnknownReferencesYieldsUnresolvedForEach
Дано service, environment, hosts[vm-pay-01] известны  Тогда HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON (validity=null без validFrom)
Дано validFrom 2026-03-01T00:00:00Z  Тогда у HAS_DEPLOYMENT и IN_ENVIRONMENT validity.validFrom = 2026-03-01T00:00:00Z
Дано ни одна ссылка не известна  Тогда unresolved по полям git-repo-url, service, environment, hosts
```

## MoSCoW
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-DM-01…RF-DM-05, RF-DM-07 | Must | Единственный источник `Deployment` (ADR 0009/0029) |
| RF-DM-06 (poc-0 / без format) | Could | Временный legacy-формат, Q-09 |
| Won't | — | Рендеринг Helm, сущность `Artifact`/`BUILDS` (вне среза ADR 0029), сравнение системы chart с системой сервиса (делает `EventProcessor`) |

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `NZ/DeployMapMapper.java` | `map`, `chartMeta`, `systemId`, `normalizedUrl`, `bindToRepository`, `payloadService`, `environmentClass` | 🟢 |
| `NT/DeployMapMapperTest.java` | 16 тестов | 🟢 |
| `NT/NormalizerTest.java` | `deployMap*`, `structuralRelationsCarryValidityFromSource`, `deploymentWithUnknownReferencesYieldsUnresolvedForEach` | 🟢 |
