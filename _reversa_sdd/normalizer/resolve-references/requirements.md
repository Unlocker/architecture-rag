# resolve-references — Связи и отложенные ссылки

> Use case модуля [`../`](../requirements.md). `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `.../src/test/java/.../normalizer/`.

## Обзор
Если связь ведёт на запись, которой нет в системе, нормализатор не создаёт фиктивный узел и не отбрасывает связь, а возвращает её в `Normalized.unresolved` как `UnresolvedReference(from, field, relationType, target, relation)`. Проектор сохраняет `relation` как отложенную (`DeferRelation`) и строит связь, когда цель появится 🟢 (`NZ/CommandSink.java:14-15,62-67`, `NZ/UnresolvedReference.java:8-17`, `EventProcessor.java:221-223`). Для ссылок, заданных не ключом записи (код системы EAM, URL репозитория), используются alias-ключи `LookupKeys` 🟢.

## Бизнес-правила
- BR-RR-01. `relation(...)` — один конец связи сама запись (`self`); проверяется существование «другого» конца: `other = from.equals(self) ? to : from` 🟢 (`CommandSink.java:62`).
- BR-RR-02. `UpsertRelation` создаётся **до** проверки существования, поэтому недопустимая пара меток/свойств (`RelationType.allows`, identity-свойства, `validity` у нетемпоральной) даёт `IllegalArgumentException` → `INVALID_PAYLOAD` даже для неразрешённой ссылки 🟢 (`CommandSink.java:63`; `CM/command/UpsertRelation.java:32-40`).
- BR-RR-03. `assertedBy` у любой связи — ключ самой записи события (`self`), даже если направление связи «чужое» (`DECOMPOSED_INTO` от системы к сервису утверждает сервис) 🟢 (`CommandSink.java:63,90`; `ScmMapper.java:68-82`).
- BR-RR-04. `link(...)` — запись сама является связью (`SERVICE_DEPENDENCY`): проверяются **оба** конца; на каждый неизвестный — отдельная `UnresolvedReference` (с полем `fromField`/`toField`); команда выдаётся только если известны оба 🟢 (`CommandSink.java:90-101`).
- BR-RR-05. Свойства связи — только присутствующие в источнике (без значений по умолчанию) 🟢 (`CommandSink.java:76`; `EamMapper.java:101-104`).
- BR-RR-06. `field` в `UnresolvedReference` — имя поля источника, не значение: `ownerTeam`, `product_id`, `platform_id`, `systemCode`, `repositoryId`, `from`, `to`, `service`, `environment`, `hosts`, `git-repo-url` 🟢.
- BR-RR-07. Alias-ключи: `systemCode` неизвестной системы → `LookupKeys.itSystemCode(code)` = `(EAM, IT_SYSTEM_CODE, code)`; `git-repo-url` неизвестного репозитория → `LookupKeys.repositoryUrl(url)` = `(SCM, REPOSITORY_URL, url)`. Если порт находит запись (`itSystemByEamCode`, `repositoryByUrl`) — используется её реальный ключ 🟢.
- BR-RR-08. На литеральный ключ `(EAM, IT_SYSTEM, <systemCode>)` ссылаться нельзя 🟢 (`ScmMapper.java:68-71`; `NT/NormalizerTest#scmServiceNeverReferencesLiteralEamCodeAsItSystemKey`).
- BR-RR-09. Потребитель дедуплицирует `unresolved` по `relation`, т.к. связь с двумя неизвестными концами приходит дважды 🟢 (`EventProcessor.java:222-223`; вне модуля).

## Функциональные требования
| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-RR-01 | `relation` добавляет `UpsertRelation` при известном втором конце | Must | AC-RR-01 |
| RF-RR-02 | `relation` добавляет `UnresolvedReference`, но не команду, при неизвестном втором конце | Must | AC-RR-02 |
| RF-RR-03 | `link` строит связь только при известных обоих концах и пишет по `UnresolvedReference` на каждый неизвестный | Must | AC-RR-03, AC-RR-04 |
| RF-RR-04 | alias-ключи для `systemCode` и `git-repo-url` | Must | AC-RR-05 |
| RF-RR-05 | порядок прихода источников не влияет: SCM до EAM порождает отложенную `DECOMPOSED_INTO` | Must | AC-RR-05 |
| RF-RR-06 | `validity` = `null`, если источник не передал время | Must | AC-RR-06 |

## НФТ
| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Надёжность | Нет фиктивных узлов: `commands` содержит `UpsertNode` только записи события | `NT/NormalizerTest#brokenReferenceBecomesUnresolvedReferenceWithoutPhantomNode` | 🟢 |
| Безопасность | `field` — имя поля, значение цели хранится только в `target` ключе (не в тексте исключения) | `NZ/UnresolvedReference.java:15` | 🟢 |

## Критерии приёмки
```gherkin
# AC-RR-01 — NormalizerTest#scmServiceMapsRelationsToSystemAndRepository
Дано известны (EAM,IT_SYSTEM,5101) [eamCode EAM-1042], (SCM,REPOSITORY,repo-payments-api)
Когда нормализуется SCM SERVICE svc-payments-api {systemCode: EAM-1042, repositoryId: repo-payments-api}
Тогда две команды UpsertRelation (DECOMPOSED_INTO 5101→сервис, IMPLEMENTED_IN сервис→репозиторий), unresolved пуст

# AC-RR-02 — NormalizerTest#brokenReferenceBecomesUnresolvedReferenceWithoutPhantomNode
Дано сервис с systemCode "EAM-UNKNOWN", система неизвестна
Тогда commands = [UpsertNode], relations нет; unresolved = одна запись {field=systemCode, relationType=DECOMPOSED_INTO, target=LookupKeys.itSystemCode("EAM-UNKNOWN"), from=ключ сервиса}

# AC-RR-03 — NormalizerTest#eamServiceDependencyMapsToDependsOnBetweenServices
Дано известны оба сервиса SCM  Когда нормализуется SERVICE_DEPENDENCY dep-payments-api-ledger-api
Тогда ровно одна UpsertRelation DEPENDS_ON from=svc-payments-api to=svc-ledger-api, свойства {kind:SYNC, protocol:HTTP, criticality:HIGH}, validity=null, assertedBy=(EAM,SERVICE_DEPENDENCY,dep-payments-api-ledger-api); UpsertNode нет; unresolved пуст

# AC-RR-04 негатив — NormalizerTest#eamServiceDependencyWithUnknownServiceIsUnresolved
Дано известен только svc-payments-api  Тогда relation нет, unresolved = [{field=to, DEPENDS_ON, target=svc-ledger-api}]
Дано не известен ни один  Тогда unresolved.field = [from, to] в этом порядке

# AC-RR-05 — NormalizerTest#scmServiceBeforeEamDefersDecomposedIntoOnItSystemCodeAlias
Дано EAM-система ещё не загружена  Когда нормализуется SCM SERVICE svc-payments-api
Тогда DECOMPOSED_INTO среди unresolved с target = LookupKeys.itSystemCode("EAM-1042"), команды DECOMPOSED_INTO нет

# AC-RR-06 — NormalizerTest#validityIsTakenOnlyFromSourceAndIsStableAcrossReplays, #structuralRelationsCarryValidityFromSource
Дано payload без validFrom/ownerSince  Тогда validity = null
Дано validFrom 2026-03-01T00:00:00Z  Тогда validity.validFrom = 2026-03-01T00:00:00Z у всех структурных связей записи
```

## MoSCoW
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-RR-01…RF-RR-03 | Must | Все связи графа идут через `CommandSink` |
| RF-RR-04, RF-RR-05 | Must | Источники приходят в произвольном порядке |
| Свойства связи | Should | Только `DEPENDS_ON` и `DEPLOYED_FROM{commit}` используют |

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `NZ/CommandSink.java` | `relation`, `link`, `result` | 🟢 |
| `NZ/UnresolvedReference.java` | `UnresolvedReference` | 🟢 |
| `NZ/ReferenceResolver.java` | `exists` | 🟢 |
| `CM/provenance/LookupKeys.java` | `itSystemCode`, `repositoryUrl` | 🟢 |
| `NT/NormalizerTest.java`, `NT/EamArchObjectMapperTest.java` | см. выше | 🟢 |
