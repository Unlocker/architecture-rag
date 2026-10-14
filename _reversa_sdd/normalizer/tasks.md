# ingestion/normalizer — задачи воспроизведения

> `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/`. Требования — [`requirements.md`](requirements.md), дизайн — [`design.md`](design.md), форматы — [`contracts.md`](contracts.md).

## Pre-requisitos (Пререквизиты)
- [ ] Модули `contracts/canonical-model`, `contracts/event-schemas`, `contracts/source-spi` собраны (`ingestion/normalizer/pom.xml:18-29`); `platform/source-stubs` — `test`-scope (`pom.xml:30-34`).
- [ ] Java 21 (records, sealed interface, pattern matching в `switch`, `switch` по строкам); без Spring, без Lombok.
- [ ] Версии зависимостей — только через BOM корневого `pom.xml`; в `pom.xml` модуля версий нет 🟢.
- [ ] Пакет `io.github.unlocker.archrag.normalizer`.

## Tarefas (Задачи)

- [ ] T-01 Типы результата и ошибок: `NormalizationResult` (sealed; `Normalized` с `List.copyOf` трёх списков; `Quarantined(errorCode, reason)`), `UnresolvedReference` (все поля `requireNonNull`), `NormalizationException(code, reason)`.
  - Origin: `NZ/NormalizationResult.java:7-35`, `NZ/UnresolvedReference.java:19-28`, `NZ/NormalizationException.java:7-20`
  - Критерий готовности: `Normalized` неизменяем; `UnresolvedReference` с `null`-полем бросает NPE; равенство по значению (используется тестом `NormalizerTest#normalizingSameEventTwiceGivesEqualResult`).
  - Уверенность: 🟢
- [ ] T-02 Порты `ReferenceResolver` (`@FunctionalInterface`, `exists(SourceKey)`) и `DeploymentLookup` (`repositoryByUrl`, `itSystemByEamCode` с дефолтом `empty`, `servicesImplementedIn`, дефолтные `eamSystemOfService`, `chartDeploymentsOfService`, `record ChartDeployment(SourceKey, long)`, константа `NONE`).
  - Origin: `NZ/ReferenceResolver.java:9-14`, `NZ/DeploymentLookup.java:16-70`
  - Критерий готовности: `NONE` на любой запрос возвращает пусто.
  - Уверенность: 🟢
- [ ] T-03 `Payload`: семантика «не передано» (null/отсутствует/пустая строка), `strip()`, строгие типы, `optionalIdRef` только `Long`/`Integer`, сообщения только с именем поля.
  - Origin: `NZ/Payload.java:15-127`
  - Критерий готовности: AC-17 ([`requirements.md`](requirements.md)); `name: 42` → `INVALID_PAYLOAD`; `product_id: "x"` и `[7201]` → `INVALID_PAYLOAD`.
  - Уверенность: 🟢
- [ ] T-04 `CanonicalMapper` и `Input`; `CommandSink` (`add`, `relation`×2, `link`, `warn`, package-private `result()`).
  - Origin: `NZ/CanonicalMapper.java:14-43`, `NZ/CommandSink.java:17-112`
  - Критерий готовности: см. [`resolve-references/tasks.md`](resolve-references/tasks.md).
  - Уверенность: 🟢
- [ ] T-05 `Normalizer`: конструктор, `standard(...)`, `normalize`, `sourceCode`, `schemaVersion`.
  - Origin: `NZ/Normalizer.java:25-108`
  - Критерий готовности: см. [`normalize-event/tasks.md`](normalize-event/tasks.md).
  - Уверенность: 🟢
- [ ] T-06 `EamMapper` (ArchObject + плоский формат, `SERVICE_DEPENDENCY`).
  - Origin: `NZ/EamMapper.java:45-215`
  - Критерий готовности: см. [`map-eam-objects/tasks.md`](map-eam-objects/tasks.md).
  - Уверенность: 🟢
- [ ] T-07 `ScmMapper` (`REPOSITORY`, `SERVICE`, alias `systemCode`, `IMPLEMENTED_IN`).
  - Origin: `NZ/ScmMapper.java:20-118`
  - Критерий готовности: AC-09, AC-10; `NormalizerTest#scmServiceBeforeEamDefersDecomposedIntoOnItSystemCodeAlias`, `ScmMapperTest#*`.
  - Уверенность: 🟢
- [ ] T-08 `CmdbMapper` (`COMPUTE_INSTANCE`, обязательные `hostname`/`kind`).
  - Origin: `NZ/CmdbMapper.java:16-58`
  - Критерий готовности: AC-02, AC-11.
  - Уверенность: 🟢
- [ ] T-09 `DeployMapMapper` (`ENVIRONMENT`, `DEPLOYMENT`, `poc-0`/`poc-1`, выбор сервиса).
  - Origin: `NZ/DeployMapMapper.java:44-253`
  - Критерий готовности: см. [`bind-deployment/tasks.md`](bind-deployment/tasks.md).
  - Уверенность: 🟢
- [ ] T-10 Маркер `NormalizerModule` и `package-info`.
  - Origin: `NZ/NormalizerModule.java:4-7`, `NZ/package-info.java:1-2`
  - Критерий готовности: `NormalizerSmokeTest#moduleMarkerIsOnClasspath`.
  - Уверенность: 🟢
- [ ] T-11 Сборка с Spring-бином снаружи: `Normalizer.standard(projector::isActive, projector)` — вне модуля.
  - Origin: `ingestion/ingestion-service/.../IngestionServiceConfiguration.java:110`
  - Критерий готовности: `EventProcessor` получает нормализатор и вызывает `normalize(event, raw)`.
  - Уверенность: 🟢

## Tarefas de Teste (Тесты)

- [ ] TT-01 Happy path по каждому источнику: `NormalizerTest#eamTeamMapsToTeam`, `#scmRepositoryMapsToRepository`, `#cmdbComputeInstanceMapsKindAndState`, `#deployMapEnvironmentInfersClassFromCode`, `#deployMapDeploymentMapsServiceEnvironmentAndHosts`.
- [ ] TT-02 Ворота: `#unsupportedEventTypeIsQuarantined`, `#unknownSourceIsQuarantined`, `#unknownSchemaVersionIsQuarantined`, `#foreignSchemaUrnIsQuarantined`, `#unknownSourceTypeIsQuarantined`.
- [ ] TT-03 Валидация без утечек: `#missingRequiredFieldIsQuarantinedWithoutPayloadValues`, `#wrongFieldTypeIsQuarantined`, `#invariantViolationReasonDoesNotLeakSourceValues`, `ScmMapperTest#unrecognizedUrlIsQuarantinedWithoutLeakingValue`.
- [ ] TT-04 Неполная запись: `#partialRecordDoesNotEmitNullsOrResets`, `#blankOptionalValueIsTreatedAsAbsent`, `#absentArchivedStaysNullInsteadOfFalse`, `#absentKindIsQuarantinedNotDefaulted`, `#absentNameOfEnvironmentAndDeploymentIsQuarantinedNotDefaulted`.
- [ ] TT-05 Отложенные ссылки: `#brokenReferenceBecomesUnresolvedReferenceWithoutPhantomNode`, `#deploymentWithUnknownReferencesYieldsUnresolvedForEach`, `#eamServiceDependencyWithUnknownServiceIsUnresolved`, `#scmServiceNeverReferencesLiteralEamCodeAsItSystemKey`.
- [ ] TT-06 Повторяемость и validity: `#normalizingSameEventTwiceGivesEqualResult`, `#eamServiceDependencyIsIdempotent`, `#validityIsTakenOnlyFromSourceAndIsStableAcrossReplays`, `#structuralRelationsCarryValidityFromSource`.
- [ ] TT-07 Тест, которого нет и который стоит добавить 🟡: «каждый `SourceSystemCode` кроме `MANUAL` имеет маппер и разбирается `sourceCode`» (Q-11); тест `normalize(event, null)`/поведения при исключении порта (Q-06). В тестах `*IT` нет.

## Ordem Sugerida (Порядок)
1. T-01, T-02 (типы и порты) → T-03 (`Payload`) → T-04 (`CommandSink`, `CanonicalMapper`).
2. T-08 (`CmdbMapper`, самый простой) → T-07 (`ScmMapper`) → T-06 (`EamMapper`) → T-09 (`DeployMapMapper`, зависит от `ScmMapper.SERVICE`, `CmdbMapper.COMPUTE_INSTANCE`, `LookupKeys`).
3. T-05 (`Normalizer`) после мапперов; T-10; затем тесты TT-*.
4. Порядок блокировок: `EamMapper.SERVICE_DEPENDENCY` использует `ScmMapper.SERVICE` (`NZ/EamMapper.java:107`), `DeployMapMapper` использует `ScmMapper.SERVICE` и `CmdbMapper.COMPUTE_INSTANCE` (`:128,210`).

## Lacunas Pendentes (🔴, открытые)
См. [`questions.md`](questions.md): Q-01 (Javadoc про `UpsertNode`), Q-02 (docs/06:81), Q-03 (docs/06:82, исправлено), Q-04 (docs/03:484), Q-05 (docs/06:74); 🟡 Q-06…Q-16.
