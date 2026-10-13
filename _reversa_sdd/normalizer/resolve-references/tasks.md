# resolve-references — задачи

## Пререквизиты
- [ ] Типы `UpsertRelation`, `NodeLabel`, `RelationType`, `Validity`, `LookupKeys` из `canonical-model`.
- [ ] T-01, T-02 из [`../tasks.md`](../tasks.md).

## Задачи
- [ ] T-RR-01 `CommandSink.relation` (две перегрузки): `other`, создание `UpsertRelation(assertedBy = self)` до проверки, `unresolved`/`commands`.
  - Origin: `NZ/CommandSink.java:41-69`
  - Критерий готовности: AC-RR-01, AC-RR-02.
  - Уверенность: 🟢
- [ ] T-RR-02 `CommandSink.link`: проверка обоих концов, отдельная `UnresolvedReference` на каждый неизвестный, команда только если известны оба.
  - Origin: `NZ/CommandSink.java:80-102`
  - Критерий готовности: AC-RR-03, AC-RR-04 (порядок `from`, `to`).
  - Уверенность: 🟢
- [ ] T-RR-03 `UnresolvedReference` и `Normalized.unresolved` (неизменяемая копия).
  - Origin: `NZ/UnresolvedReference.java:19-28`, `NZ/NormalizationResult.java:23-27`
  - Критерий готовности: `result()` не отдаёт изменяемых списков.
  - Уверенность: 🟢
- [ ] T-RR-04 Alias-ключи SCM→EAM и DEPLOYMAP→SCM через `DeploymentLookup` + `LookupKeys`.
  - Origin: `NZ/ScmMapper.java:72-82`, `NZ/DeployMapMapper.java:194-207`
  - Критерий готовности: AC-RR-05; `NormalizerTest#scmServiceNeverReferencesLiteralEamCodeAsItSystemKey`; `DeployMapMapperTest#unknownRepositoryDefersDeployedFromOnAliasAndKeepsServiceFallback`.
  - Уверенность: 🟢

## Тесты
- [ ] TT-RR-01 `NormalizerTest#scmServiceMapsRelationsToSystemAndRepository` (позитив).
- [ ] TT-RR-02 `#brokenReferenceBecomesUnresolvedReferenceWithoutPhantomNode`, `#scmServiceBeforeEamDefersDecomposedIntoOnItSystemCodeAlias` (негатив/порядок).
- [ ] TT-RR-03 `#eamServiceDependencyWithUnknownServiceIsUnresolved`, `#deploymentWithUnknownReferencesYieldsUnresolvedForEach`, `EamArchObjectMapperTest#referenceToNotYetLoadedObjectIsUnresolvedNotAPhantomNode`.
- [ ] TT-RR-04 (нет в коде 🟡) Тест на недопустимую пару меток в неразрешённой ссылке (BR-RR-02) и на `exists(реальныйКлюч)=false` после `lookup`.

## Порядок
T-RR-03 → T-RR-01 → T-RR-02 → T-RR-04.

## Открытые
Q-06 ([`../questions.md`](../questions.md)).
