# map-eam-objects — Маппинг объектов EAM

> Use case модуля [`../`](../requirements.md). `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `.../src/test/java/.../normalizer/`. Форматы — [`../contracts.md`](../contracts.md) §5.1.

## Обзор
`EamMapper` разбирает два формата payload источника `urn:corp:eam`: **ArchObject** реального EAM API (`PLATFORM`, `SOLUTION`, `IT_SYSTEM` с `attrs`; ключ — целочисленный `id`) и **плоский** payload старой заглушки (`TEAM`, `SERVICE_DEPENDENCY`, `IT_SYSTEM` без `attrs`) 🟢 (`NZ/EamMapper.java:20-45`). Иерархия «платформа → решение → система» задаётся полями-ссылками `attrs.platform_id` (решение), `attrs.product_id` (система → решение); `attrs.platform_id` системы сохраняется свойством узла (ADR 0031) 🟢.

## Бизнес-правила
- BR-EAM-01. Выбор ветки по `sourceType` ключа: `PLATFORM`, `SOLUTION` → ArchObject (всегда); `IT_SYSTEM` → ArchObject, если `attrs` — объект, иначе плоский; `TEAM`, `SERVICE_DEPENDENCY` → плоские; иное → `UNKNOWN_SOURCE_TYPE "unknown EAM sourceType"` 🟢 (`EamMapper.java:69-116`).
- BR-EAM-02. ArchObject без `attrs` (отсутствует/`null`) для `PLATFORM`/`SOLUTION` → `MISSING_REQUIRED_FIELD "required field is missing: attrs"`; `attrs` не объект → `INVALID_PAYLOAD` 🟢 (`:134-137`, `Payload.java:49-51`).
- BR-EAM-03. Поля ArchObject `id`, `ver`, `datetime` не читаются; ключ, версия и время берутся из события 🟢.
- BR-EAM-04. Ссылки (`platform_id`, `product_id`) — одиночное целое (`Long`/`Integer`) или `null`/нет; строка, список, дробь → `INVALID_PAYLOAD`; прежние списочные поля (`solution`, `platform`) не читаются 🟢 (`Payload.java:113-122`; `NT/EamArchObjectMapperTest#legacyListFieldsAreNotReadAndListValueIsQuarantined`, `#nonIntegerReferenceIsQuarantinedWithoutValue`).
- BR-EAM-05. `Solution.platformId`/`ITSystem.platformId` — `Long` из той же ссылки, `null` если нет; значение ≤ 0 → `IllegalArgumentException` модели → `INVALID_PAYLOAD` 🟢 (`EamMapper.java:157,179`; `CM/node/Solution.java:18`, `CM/node/ITSystem.java:22`).
- BR-EAM-06. Критичность `IT_SYSTEM` (ArchObject): `HIGH|MEDIUM|LOW` (регистр не важен) → `Criticality`; другое непустое значение → `criticality=null`, `criticalityRaw=<сырое>`, warning `UNMAPPED_CRITICALITY`, `DEBUG`-лог без значения; отсутствует → `criticality=null`, `criticalityRaw=null`, warning нет 🟢 (`:161-168`).
- BR-EAM-07. Плоский `IT_SYSTEM`: `criticality` через `Criticality.valueOf(UPPER)` (включая `CRITICAL`), неизвестное → `criticality=null`, `criticalityRaw=<сырое>`, warning `UNMAPPED_CRITICALITY` (К-10, UNLOCKER-287); `eamCode` не заполняется («только реплей старого журнала»), `ITSystem(name,status,criticality,description)` 🟢 (`:77-84,120-126`).
- BR-EAM-08. `attrs.eam_id` → `ITSystem.eamCode` (по нему SCM находит систему) 🟢 (`:177`).
- BR-EAM-09. Связи: `USES_PLATFORM` Solution→`(EAM,PLATFORM,platform_id)`, `USES_SOLUTION` ITSystem→`(EAM,SOLUTION,product_id)`, `OWNED_BY` ITSystem→`(EAM,TEAM,ownerTeam)`; `validity` для `OWNED_BY` — из `ownerSince`, для `USES_*` — всегда `null` 🟢. Прямой связи «система → платформа» нет 🟢 (`:180`).
- BR-EAM-10. `SERVICE_DEPENDENCY`: `from`/`to` обязательны (коды сервисов SCM); свойства связи — только переданные `kind`, `protocol`, `criticality` (последнее как `Criticality.name()`); `validFrom` → `Validity(validFrom,null)`; `UpsertNode` не выдаётся 🟢 (`:100-115`).
- BR-EAM-11. Плоский `dependsOn` (непустой после отбрасывания пустых элементов) связей не даёт, только warning `DEPENDS_ON_NOT_SUPPORTED` (`DEPENDS_ON` допустим лишь Service→Service) 🟢 (`:96-98`).
- BR-EAM-12. Исчезновение/`null` ссылки не порождает команд закрытия; `null`-ссылка = «не передано» 🟢 (`EamMapper.java:208-214`, `NT/EamArchObjectMapperTest#singleReferenceFormsNumberAndNullAreAccepted`).

## Функциональные требования
| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-EAM-01 | `PLATFORM` → `Platform(name,type,version,lifecycle,description)` | Must | AC-EAM-01 |
| RF-EAM-02 | `SOLUTION` → `Solution` + `USES_PLATFORM` | Must | AC-EAM-02 |
| RF-EAM-03 | `IT_SYSTEM` (ArchObject) → `ITSystem` с `eamCode`, `platformId`, `criticalityRaw` + `USES_SOLUTION` + `OWNED_BY` | Must | AC-EAM-03, AC-EAM-04 |
| RF-EAM-04 | Разбор ссылок только одиночным целым | Must | AC-EAM-05 |
| RF-EAM-05 | `TEAM` → `Team(name,type)` | Must | AC-EAM-06 |
| RF-EAM-06 | `SERVICE_DEPENDENCY` → `DEPENDS_ON` (без `UpsertNode`) | Must | AC-RR-03 ([`../resolve-references/requirements.md`](../resolve-references/requirements.md)) |
| RF-EAM-07 | Плоский `IT_SYSTEM` (legacy) без `eamCode` | Could | AC-EAM-07 |
| RF-EAM-08 | Warnings `UNMAPPED_CRITICALITY`, `DEPENDS_ON_NOT_SUPPORTED` | Should | AC-EAM-04, AC-15 ([`../requirements.md`](../requirements.md)) |

## НФТ
| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Сырое значение неизвестной критичности не логируется (DEBUG без значения) и не попадает в `warnings`; хранится в узле как `criticalityRaw` | `NZ/EamMapper.java:164-168` | 🟢 |
| Безопасность | Нецелая ссылка не раскрывается в причине | `NT/EamArchObjectMapperTest#nonIntegerPlatformIdIsQuarantinedWithoutValue` | 🟢 |

## Критерии приёмки
```gherkin
# AC-EAM-01 — EamArchObjectMapperTest#platformMapsToPlatformNode
Дано PLATFORM 3101 attrs{name:"Kubernetes Platform", status:"ACTIVE", lifecycle:"PRODUCTION", description:"Shared container runtime"}
Тогда UpsertNode(key (EAM,PLATFORM,3101), Platform("Kubernetes Platform", null, null, "PRODUCTION", "Shared container runtime")); связей нет

# AC-EAM-02 — #solutionMapsToSolutionAndUsesPlatformByPlatformId
Дано известен (EAM,PLATFORM,3102); SOLUTION 7202 attrs{name:"Ledger Engine", lifecycle, description, platform_id:3102L}
Тогда UpsertNode(Solution(..., platformId=3102)) и USES_PLATFORM (EAM,SOLUTION,7202)→(EAM,PLATFORM,3102); unresolved пуст

# AC-EAM-03 — #itSystemMapsToItSystemWithEamCodeAndUsesSolution
Дано известен (EAM,SOLUTION,7201); IT_SYSTEM 5101 attrs{name, eam_id:"EAM-1042", status, criticality:"HIGH", description, product_id:7201, platform_id:3101}
Тогда ITSystem(name,"ACTIVE",HIGH,description,"EAM-1042",null,3101) и единственная связь USES_SOLUTION 5101→7201 (связи на платформу нет)

# AC-EAM-04 — #unknownCriticalityIsKeptRawAndFlaggedNotDropped, #knownCriticalityIsCaseInsensitiveAndAbsentCriticalityStaysNull
Дано attrs.criticality = "BUSINESS-CRITICAL-X"  Тогда criticality=null, criticalityRaw="BUSINESS-CRITICAL-X", warnings=["UNMAPPED_CRITICALITY"]
Дано "low"  Тогда Criticality.LOW; без criticality — null и warnings пуст

# AC-EAM-05 негатив — #nonIntegerReferenceIsQuarantinedWithoutValue, #legacyListFieldsAreNotReadAndListValueIsQuarantined, #nonIntegerPlatformIdIsQuarantinedWithoutValue
Дано product_id = "SECRET-VALUE" или [7201]  Тогда Quarantined INVALID_PAYLOAD; reason без значения
Дано legacy-поля solution/platform = списки  Тогда связей нет (поля не читаются)

# AC-EAM-06 — NormalizerTest#eamTeamMapsToTeam, #blankOptionalValueIsTreatedAsAbsent
Дано TEAM {name:"N", type:"  "}  Тогда Team("N", null)

# AC-EAM-07 — #legacyFlatItSystemGetsNoEamCodeSoAliasCannotHealOntoIt, NormalizerTest#eamItSystemMapsOwner
Дано плоский IT_SYSTEM {name, criticality:"HIGH"}  Тогда eamCode=null, criticality=HIGH
Дано {ownerTeam:"TEAM-PAY"} и (EAM,TEAM,TEAM-PAY) известен  Тогда OWNED_BY, validity=null

# AC-EAM-08 негатив — #archObjectWithoutNameIsQuarantined
Дано PLATFORM attrs без name, либо плоский payload без attrs  Тогда Quarantined MISSING_REQUIRED_FIELD

# AC-EAM-09 — #changedReferenceYieldsOnlyTheNewRelationSoTheOldOneIsNotAsserted
Дано SOLUTION 7201, platform_id сменился 3101→3103  Тогда второй результат содержит только USES_PLATFORM на 3103 (старой/закрытия нет)
```

## MoSCoW
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-EAM-01…RF-EAM-04 | Must | Основной формат EAM (`docs/06-eam-integration.md`) |
| RF-EAM-05, RF-EAM-06 | Must | Источники `Team` и `DEPENDS_ON` |
| RF-EAM-07 | Could | «Только реплей старого журнала» (`EamMapper.java:32-33`) |
| RF-EAM-08 | Should | Информационные |
| Won't | — | `mrelations`, `CloseAssertion`, связь «система → платформа» |

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `NZ/EamMapper.java` | `map`, `mapArchObject`, `reference`, `criticality` | 🟢 |
| `NT/EamArchObjectMapperTest.java` | 16 тестов | 🟢 |
| `NT/NormalizerTest.java` | `eam*` тесты (7) | 🟢 |
