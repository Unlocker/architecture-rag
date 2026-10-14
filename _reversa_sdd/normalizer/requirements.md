# ingestion/normalizer — требования

> Reversa Writer, R.6a. Уровень модуля. Пути относительно корня репозитория; `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/`. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел.
> Use case: [`normalize-event/`](normalize-event/), [`resolve-references/`](resolve-references/), [`map-eam-objects/`](map-eam-objects/), [`bind-deployment/`](bind-deployment/). Внешний контракт — [`contracts.md`](contracts.md). Вопросы к человеку — [`questions.md`](questions.md).

## Визуальная картина

`CanonicalEvent` + `RawPayloadRef` → `Normalizer.normalize` → `NormalizationResult` (`Normalized(commands, unresolved, warnings)` либо `Quarantined(errorCode, reason)`) 🟢 (`NZ/Normalizer.java:54-86`, `NZ/NormalizationResult.java:7-35`).

## Visão Geral (Обзор)

Модуль — чистая библиотека без Spring и без ввода-вывода (`ingestion/normalizer/pom.xml:15-34`: зависит только от `canonical-model`, `event-schemas`, `source-spi`; `source-stubs` только в `test`) 🟢. Он превращает событие `architecture.asset.upserted.v1` с source-специфичным `payload` (`Map<String,Object>`) в `GraphCommand`'ы канонической модели. Это **единственное** место, где существует source DTO (`NZ/CanonicalMapper.java:10-13`) 🟢. Проверки существования ссылок и привязки deploy map к сервису модуль не делает сам, а запрашивает через порты `ReferenceResolver` и `DeploymentLookup`, реализация которых подключается снаружи (`NZ/ReferenceResolver.java:5-14`, `NZ/DeploymentLookup.java:7-16`) 🟢. Потребитель — `EventProcessor` проектора (`ingestion/graph-projector/.../EventProcessor.java:213`), конфигурация — `IngestionServiceConfiguration.java:110` (`Normalizer.standard(projector::isActive, projector)`) 🟢.

## Ответственности

- Отклонить событие неподдерживаемого типа, неизвестного источника или неподдерживаемой версии схемы (карантин с кодом) 🟢 (`NZ/Normalizer.java:55-66`).
- Собрать `SourceRecord` из события и `raw.contentHash()` 🟢 (`NZ/Normalizer.java:68-74`).
- Выбрать `CanonicalMapper` источника и передать ему `Input(record, payload, time)` 🟢 (`NZ/Normalizer.java:59,77`).
- Разобрать payload четырёх источников (EAM, SCM, CMDB, DEPLOYMAP) в `UpsertNode`/`UpsertRelation` 🟢 (`NZ/EamMapper.java`, `ScmMapper.java`, `CmdbMapper.java`, `DeployMapMapper.java`).
- Не создавать фиктивных узлов: связь с неизвестным концом фиксируется как `UnresolvedReference` 🟢 (`NZ/CommandSink.java:62-67`).
- Не затирать известные поля неполной записью: «не передано» → `null`, а не значение по умолчанию 🟢 (`NZ/Payload.java:12-14,31-41`).
- Переводить любые нарушения входа в `Quarantined` с кодом и причиной без значений из источника 🟢 (`NZ/Normalizer.java:78-84`).
- Возвращать предупреждения о потерянных данных кодами без значений 🟢 (`NZ/EamMapper.java:97,166`, `NZ/DeployMapMapper.java:223`).

**Вне ответственности** 🟢: запись в Neo4j/PostgreSQL/S3, выбор `gid`, дедупликация и версии (это `EventProcessor`), tombstone-события `architecture.asset.deleted.v1` (нормализатор на них отвечает `UNSUPPORTED_EVENT_TYPE`; `NT/NormalizerTest#unsupportedEventTypeIsQuarantined`), закрытие связей (делает проектор: `closeReplacedRelations`), сравнение системы chart с системой сервиса (`NT/DeployMapMapperTest#systemMismatchGivesOrdinaryBindingWithoutConflictOrWarning`).

## Regras de Negócio (Бизнес-правила)

- BR-01. Обрабатывается только тип `architecture.asset.upserted.v1`; всё прочее — `UNSUPPORTED_EVENT_TYPE` 🟢 (`NZ/Normalizer.java:55-57`).
- BR-02. Источник — `urn:corp:<код>`, где код ∈ `eam|scm|cmdb|deploymap` (регистр значим); иначе (в том числе `urn:corp:manual` при существующем `SourceSystemCode.MANUAL`) — `UNKNOWN_SOURCE` 🟢 (`NZ/Normalizer.java:58-62,92-103`; `contracts/canonical-model/.../SourceSystemCode.java:5`).
- BR-03. Версия схемы — суффикс `dataschema` после `urn:corp:schema:asset-upserted:`; для всех четырёх мапперов поддерживается только `"1"`; иной префикс или версия — `UNKNOWN_SCHEMA_VERSION` 🟢 (`NZ/Normalizer.java:63-66,105-107`; `EamMapper.java:61-63`, `ScmMapper.java:42-44`, `CmdbMapper.java:26-28`, `DeployMapMapper.java:75-77`).
- BR-04. `SourceRecord`: `key=(source из URN, data.sourceType, data.sourceId)`, `sourceVersion=data.sourceVersion().value()`, `contentHash=raw.contentHash()`, `fetchedAt=event.time()`, `active=true`; `active=false` нормализатор не выставляет никогда 🟢 (`NZ/Normalizer.java:68-74`).
- BR-05. «Поле не передано» = отсутствие ключа, `null` или пустая/пробельная строка; значения строк `strip()` 🟢 (`NZ/Payload.java:31-41`). Значение неверного типа — `INVALID_PAYLOAD` 🟢 (`NZ/Payload.java:124-126`).
- BR-06. Необязательное поле узла, не переданное источником, остаётся `null` (никаких значений по умолчанию); обязательные поля — `MISSING_REQUIRED_FIELD` 🟢 (`NT/NormalizerTest#partialRecordDoesNotEmitNullsOrResets`, `#absentArchivedStaysNullInsteadOfFalse`, `#absentKindIsQuarantinedNotDefaulted`).
- BR-07. Связь, у которой неизвестен второй конец, не выдаётся командой, а попадает в `unresolved` целиком (`UpsertRelation` внутри); фиктивный узел не создаётся 🟢 (`NZ/CommandSink.java:62-67`).
- BR-08. `Validity` создаётся только как `Validity(validFrom, null)` при наличии `validFrom`/`ownerSince` в payload, иначе `null` («выбор за проектором») 🟢 (`NZ/EamMapper.java:94,112,192`, `ScmMapper.java:115-117`, `DeployMapMapper.java:240-242`).
- BR-09. Причина карантина не содержит значений из источника: только имя поля/аннотации или фиксированный текст 🟢 (`NZ/Normalizer.java:81-83`; `NZ/Payload.java:27,125`; `NZ/DeployMapMapper.java:158,189`).
- BR-10. Нарушение инварианта record'а модели (`IllegalArgumentException` из конструктора) → `INVALID_PAYLOAD` с фиксированным текстом `payload violates canonical model invariants` 🟢 (`NZ/Normalizer.java:80-84`; `NT/NormalizerTest#cmdbKindMismatchIsQuarantined`).
- BR-11. Любое другое `RuntimeException` (NPE, ошибка порта `ReferenceResolver`/`DeploymentLookup`) **не перехватывается** и уходит вызывающему 🟢 (`NZ/Normalizer.java:76-84` — только два `catch`) — как реагирует `EventProcessor`, см. [`questions.md`](questions.md) Q-02 🟡.
- BR-12. Нормализатор не логирует значений; единственная запись в журнал — `DEBUG` без значения при `UNMAPPED_CRITICALITY` 🟢 (`NZ/EamMapper.java:167`).

## Requisitos Funcionais (Функциональные требования)

| ID | Требование | Приоритет | Критерий приёмки |
|---|---|---|---|
| RF-01 | `Normalizer.normalize(event, raw)` возвращает `Normalized` либо `Quarantined`; не бросает исключений для входов, нарушающих контракт модели (BR-10) 🟢 | Must | AC-01, AC-02 |
| RF-02 | Ворота: тип события → источник → версия схемы, в этом порядке; первое нарушение завершает обработку 🟢 | Must | AC-03, AC-04, AC-05 |
| RF-03 | `Normalizer.standard(resolver[, lookup])` регистрирует `EamMapper`, `ScmMapper(lookup)`, `CmdbMapper`, `DeployMapMapper(lookup)`; без `lookup` — `DeploymentLookup.NONE` 🟢 (`NZ/Normalizer.java:39-47`) | Must | AC-06 |
| RF-04 | `EamMapper`: `PLATFORM`, `SOLUTION`, `IT_SYSTEM` (ArchObject/плоский), `TEAM`, `SERVICE_DEPENDENCY` 🟢 | Must | AC-07, AC-08 (см. [`map-eam-objects/`](map-eam-objects/)) |
| RF-05 | `ScmMapper`: `REPOSITORY` (URL → `GitUrls.normalize`), `SERVICE` (+ `DECOMPOSED_INTO`, `IMPLEMENTED_IN`) 🟢 | Must | AC-09, AC-10 |
| RF-06 | `CmdbMapper`: `COMPUTE_INSTANCE`; `kind` обязателен; поле `environment` игнорируется 🟢 | Must | AC-11 |
| RF-07 | `DeployMapMapper`: `ENVIRONMENT`, `DEPLOYMENT` (форматы `deploymap-poc-0`/`-1`) 🟢 | Must | AC-12 (см. [`bind-deployment/`](bind-deployment/)) |
| RF-08 | `CommandSink.relation`/`link` порождают `UpsertRelation` или `UnresolvedReference` по `ReferenceResolver` 🟢 | Must | AC-13, AC-14 (см. [`resolve-references/`](resolve-references/)) |
| RF-09 | Alias-ключи для неразрешённых ссылок: `LookupKeys.itSystemCode(systemCode)` (SCM→EAM), `LookupKeys.repositoryUrl(url)` (DEPLOYMAP→SCM) 🟢 | Must | AC-14 |
| RF-10 | Предупреждения `warnings` — только коды: `DEPENDS_ON_NOT_SUPPORTED`, `UNMAPPED_CRITICALITY`, `DEPLOYMENT_SERVICE_UNRESOLVED` 🟢 | Should | AC-15 |
| RF-11 | Повторная нормализация того же события при том же состоянии портов даёт равный результат 🟢 | Must | AC-16 |
| RF-12 | `Payload` — единая семантика чтения полей (BR-05) 🟢 | Must | AC-17 |

## Requisitos Não Funcionais (Нефункциональные требования)

| Тип | Требование (выведено из кода) | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Тексты источника недоверенные: причины карантина и warnings не содержат значений payload | `NZ/Normalizer.java:81-83`, `NZ/NormalizationException.java:3-6`, `NZ/DeployMapMapper.java:158`; `NT/NormalizerTest#missingRequiredFieldIsQuarantinedWithoutPayloadValues`, `#invariantViolationReasonDoesNotLeakSourceValues`, `NT/DeployMapMapperTest#invalidAnnotationValuesAreQuarantinedWithoutLeakingValues`, `NT/ScmMapperTest#unrecognizedUrlIsQuarantinedWithoutLeakingValue` | 🟢 |
| Безопасность | Исключение разбора URL не наследует cause (в сообщении `URISyntaxException` был бы весь вход) | `contracts/canonical-model/.../node/GitUrls.java:50-51` (используется в `NZ/ScmMapper.java:106-112`) | 🟢 |
| Надёжность / повтор | Нормализация без побочных эффектов (кроме `DEBUG`-лога), безопасна при повторе | `NZ/Normalizer.java:18-19`; `NT/NormalizerTest#normalizingSameEventTwiceGivesEqualResult`, `#eamServiceDependencyIsIdempotent` | 🟢 |
| Надёжность | Результат детерминирован при фиксированном ответе портов; время `now()` не используется: `fetchedAt`/`observedAt` берутся из `event.time()` | `NZ/Normalizer.java:73`, `NZ/DeployMapMapper.java:105`; `NT/NormalizerTest#validityIsTakenOnlyFromSourceAndIsStableAcrossReplays` | 🟢 |
| Потокобезопасность | `Normalizer` после конструктора не меняет состояние; `CommandSink` создаётся на каждый вызов | `NZ/Normalizer.java:30-36,75` | 🟡 (`EnumMap` не потокобезопасен при записи, но запись только в конструкторе; мапперы без состояния — `DeployMapMapper.java:59` хранит только `lookup`) |
| Расширяемость | Новый источник = новая реализация `CanonicalMapper` + строка в `Normalizer.sourceCode` + регистрация | `NZ/CanonicalMapper.java:14-27`, `NZ/Normalizer.java:92-103` | 🟢 |

> Нагрузочных/временных требований в модуле нет (таймаутов, кэшей, retry — нет); строки не добавлены.

## Critérios de Aceitação

Тесты — в `NT/` (JUnit 5 + AssertJ, surefire, `*Test`; `*IT` в модуле нет 🟢).

```gherkin
# AC-01 (RF-01, позитив) — NT/NormalizerTest#eamTeamMapsToTeam
Дано событие urn:corp:eam, TEAM/TEAM-PAY, payload {name: "Payments Team"}, sourceVersion "5", raw.contentHash "abc123", event.time T
Когда вызывается normalize
Тогда результат Normalized; commands[0] = UpsertNode(Team("Payments Team", null)) с record.key=(EAM,TEAM,TEAM-PAY), sourceVersion "5", contentHash "abc123", fetchedAt T

# AC-02 (RF-01, негатив) — NT/NormalizerTest#invariantViolationReasonDoesNotLeakSourceValues, #cmdbKindMismatchIsQuarantined
Дано CMDB COMPUTE_INSTANCE c payload {hostname: "h", kind: "PHYSICAL_SERVER", hypervisorRef: "hv"}
Когда вызывается normalize
Тогда Quarantined("INVALID_PAYLOAD", "payload violates canonical model invariants")

# AC-03 (RF-02, негатив) — #unsupportedEventTypeIsQuarantined
Дано событие типа architecture.asset.deleted.v1
Тогда Quarantined с errorCode UNSUPPORTED_EVENT_TYPE

# AC-04 (RF-02, негатив) — #unknownSourceIsQuarantined
Дано source = urn:corp:hr
Тогда Quarantined UNKNOWN_SOURCE

# AC-05 (RF-02, негатив) — #unknownSchemaVersionIsQuarantined, #foreignSchemaUrnIsQuarantined
Дано dataschema = urn:corp:schema:asset-upserted:2 (или urn:other:thing:1)
Тогда Quarantined UNKNOWN_SCHEMA_VERSION; причина "dataschema version is not supported"

# AC-06 (RF-03) — #unknownSourceTypeIsQuarantined
Дано EAM, sourceType WIDGET
Тогда Quarantined UNKNOWN_SOURCE_TYPE

# AC-07/AC-08 — см. map-eam-objects/requirements.md
# AC-09 (RF-05) — #scmServiceMapsRelationsToSystemAndRepository
Дано в портах известны (EAM,IT_SYSTEM,5101), eamCode EAM-1042→5101, (SCM,REPOSITORY,repo-payments-api)
Когда нормализуется SCM SERVICE svc-payments-api
Тогда UpsertNode(Service("payments-api",null,null,null)) + DECOMPOSED_INTO 5101→сервис + IMPLEMENTED_IN сервис→репозиторий; unresolved пуст

# AC-10 (RF-05, негатив) — NT/ScmMapperTest#unrecognizedUrlIsQuarantinedWithoutLeakingValue
Дано REPOSITORY с url, который GitUrls не распознаёт
Тогда Quarantined INVALID_PAYLOAD, причина "repository url is not a recognized git url" без значения

# AC-11 (RF-06) — #cmdbComputeInstanceMapsKindAndState, #absentKindIsQuarantinedNotDefaulted
Дано CMDB payload {hostname, kind: "virtual_machine"|..., state}  → ComputeInstance с kind по enum (регистр не важен)
Дано payload без kind → MISSING_REQUIRED_FIELD (не UNSPECIFIED)

# AC-12 (RF-07) — см. bind-deployment/requirements.md
# AC-13/AC-14 (RF-08/09) — см. resolve-references/requirements.md
# AC-15 (RF-10) — #eamItSystemLevelDependsOnIsNotMappedAndWarns
Дано плоский IT_SYSTEM {name: "S", dependsOn: ["EAM-2001"]}
Тогда relations пусты, warnings = ["DEPENDS_ON_NOT_SUPPORTED"]

# AC-16 (RF-11) — #normalizingSameEventTwiceGivesEqualResult
Дано одно и то же событие  → normalize дважды → результаты равны (equals)

# AC-17 (RF-12) — #blankOptionalValueIsTreatedAsAbsent, #wrongFieldTypeIsQuarantined, #missingRequiredFieldIsQuarantinedWithoutPayloadValues
Дано TEAM {name: "N", type: "  "} → Team("N", null)
Дано TEAM {name: 42} → INVALID_PAYLOAD
Дано IT_SYSTEM {name: null, description: "SECRET-TEXT"} → MISSING_REQUIRED_FIELD, reason содержит "name", не содержит "SECRET-TEXT"
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01, RF-02, RF-03 (ворота и диспетчеризация) | Must | Каждое событие проходит через `Normalizer.normalize` (`EventProcessor.java:213`) |
| RF-04…RF-07 (мапперы) | Must | По одному на источник PoC; без них событие уходит в карантин |
| RF-08, RF-09 (отложенные ссылки) | Must | Порядок прихода источников произвольный; иначе потеря связей/фиктивные узлы |
| RF-11 (повторяемость) | Must | Replay/rebuild гоняют те же события |
| RF-10 (warnings) | Should | Информативно; на корректность графа не влияет |
| Блок `DeploymentLookup` по умолчанию (`NONE`) | Could | Для тестов и сборки без графа (`NZ/DeploymentLookup.java:19-35`) |
| Формат `deploymap-poc-0` и плоский `IT_SYSTEM` | Could | Временные/legacy (см. [`questions.md`](questions.md) Q-05, Q-06) |
| Won't | — | Обработка `deleted`/`snapshot-complete` (вне модуля), `Namespace`/`KubernetesCluster` (мапперов нет, ADR 0011) |

## Rastreabilidade de Código (Трассировка)

| Файл | Класс | Покрытие |
|---|---|---|
| `NZ/Normalizer.java` | `Normalizer` | 🟢 |
| `NZ/CanonicalMapper.java` | `CanonicalMapper`, `Input` | 🟢 |
| `NZ/CommandSink.java` | `CommandSink` | 🟢 |
| `NZ/Payload.java` | `Payload` (package-private) | 🟢 |
| `NZ/NormalizationResult.java`, `UnresolvedReference.java`, `NormalizationException.java` | типы результата | 🟢 |
| `NZ/ReferenceResolver.java`, `DeploymentLookup.java` | порты | 🟢 |
| `NZ/EamMapper.java` | [`map-eam-objects/`](map-eam-objects/) | 🟢 |
| `NZ/ScmMapper.java`, `CmdbMapper.java` | design.md §5 | 🟢 |
| `NZ/DeployMapMapper.java` | [`bind-deployment/`](bind-deployment/) | 🟢 |
| `NZ/NormalizerModule.java`, `package-info.java` | маркер; `NT/NormalizerSmokeTest#moduleMarkerIsOnClasspath` | 🟢 (n/a по поведению) |

Тесты: 71 `@Test` — `NormalizerTest` 35, `EamArchObjectMapperTest` 16, `DeployMapMapperTest` 16, `ScmMapperTest` 3, `NormalizerSmokeTest` 1 (подсчёт по `grep -c '@Test'`) 🟢. Прямых тестов нет у `Payload` (покрыт через мапперы) и у `CommandSink.link` с неизвестным `from` при известном `to` отдельно (покрыт частично `NormalizerTest#eamServiceDependencyWithUnknownServiceIsUnresolved`) 🟡.
