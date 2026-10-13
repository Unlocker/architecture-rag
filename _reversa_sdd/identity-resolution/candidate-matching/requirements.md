# candidate-matching: требования

> Use case модуля [`ingestion/identity-resolution`](../requirements.md). Алиасы `IR`, `MIG`, `IT`, `EP` — как в модульных файлах. Дизайн: [`design.md`](design.md).

## Обзор
По каждому upsert-событию снять признаки идентичности (hostname, URL репозитория, имя, владелец), заменить ими сохранённый набор записи и записать кандидатов на совпадение с другими записями того же семейства меток. Узлы не объединяются, `gid` не меняется (ADR 0019) 🟢 (`IR/IdentityCandidates.java:8-15`).

## Требования

| ID | Требование | MoSCoW | Evidence |
|----|-----------|--------|----------|
| UC2-01 | Признаки по метке: `ComputeInstance` -> `HOSTNAME`; `Repository` -> `REPOSITORY_URL`; `ITSystem`, `Service`, `Team` -> `NAME`; остальные метки без признаков | Must | `IR/FeatureExtractor.java:50-57` 🟢 |
| UC2-02 | `OWNER` только у `ITSystem`/`Service`: `gid` команды из первой `OWNED_BY` с `from == key`; нет `gid` команды в карте -> нет признака | Must | `:58-67` 🟢 |
| UC2-03 | Нормализация значений (см. `../contracts.md` §3) делает формы одного URL/имени равными; пустой результат — признака нет | Must | `IR/FeatureNormalizer.java:21-62` 🟢 |
| UC2-04 | Набор признаков ключа заменяется целиком при каждом `record` | Must | `IR/PostgresIdentityCandidates.java:171-188` 🟢 |
| UC2-05 | Кандидат создаётся при совпадении сильного признака с другой записью того же `label_family`, без общего `gid` | Must | `:205-212,352-400` 🟢 |
| UC2-06 | `score = min(1, сумма весов различных видов)`; `OWNER` добавляется к паре, найденной по сильному признаку, если у партнёра тот же `OWNER` | Must | `:383-398`; `IR/FeatureKind.java:38-47` 🟢 |
| UC2-07 | Предохранитель `MAX_MATCHES = 50` | Must | `:195,374-378` 🟢 |
| UC2-08 | Идемпотентность: повтор не плодит строк, `first_seen_at` и `status` не меняются | Must | `:216-223` 🟢 |
| UC2-09 | `forget` убирает признаки (tombstone), кандидаты остаются | Should | `:266-282` 🟢 |
| UC2-10 | `candidatesOf(key)` читает пары, где ключ слева или справа, в порядке ключей | Should | `:224-231,284-314` 🟢 |
| UC2-11 | Конкурентные `record` одного значения не теряют пару (advisory-lock) | Must | `:185-188,246-247,316-331` 🟢 |

## Критерии приёмки

> `IdentityCandidateIT` гоняет события через `EventProcessor` (проектор + Neo4j + PostgreSQL), а не вызывает `PostgresIdentityCandidates` напрямую; прямые вызовы — только `candidatesOf` и `approve`.

```gherkin
Сценарий 1 (позитивный): общий hostname
  Дано две записи CMDB/COMPUTE_INSTANCE (подтипы VIRTUAL_MACHINE и PHYSICAL_SERVER) с hostname "App-01.<s>.corp." и "app-01.<s>.corp" и разными gid
  Когда для второй вызван record
  Тогда в identity_candidate одна строка (OPEN, matched = [HOSTNAME app-01.corp], score 0.60), узлы остались двумя
  # IdentityCandidateIT.sameHostnameInTwoRecordsKeepsTwoNodesAndRecordsOneOpenCandidate

Сценарий 2 (усиление владельцем): имя + владелец
  Дано две EAM/IT_SYSTEM с именами "Billing <t>" и "BILLING_<T>" (после NFKC/lower-case равны) и одним владельцем-Team
  Тогда matched = [NAME, OWNER] именно в этом порядке, score = 0.60 (0.4 + 0.2)
  # sameNameAndOwnerGivesCandidateWithBothFeatures

Сценарий 3 (негативный): только владелец
  Дано записи с одним OWNER, но разными именами
  Тогда кандидатов нет
  # sameOwnerWithDifferentNamesGivesNoCandidate

Сценарий 4 (негативный): разные семейства
  Дано EAM/IT_SYSTEM и SCM/SERVICE с одинаковым именем
  Тогда кандидатов нет (label_family различается)
  # itSystemAndServiceWithSameNameAreNotCandidates

Сценарий 5 (негативный): общий gid
  Дано записи связаны crosswalk (один gid)
  Тогда кандидата нет
  # recordsLinkedByCrosswalkShareGidAndGiveNoCandidate

Сценарий 6 (идемпотентность): replay
  Когда то же событие обработано повторно
  Тогда одна строка, first_seen_at прежний
  # replayKeepsOneCandidateAndFirstSeenAt

Сценарий 7 (tombstone): забытые признаки
  Дано запись удалена (forget)
  Когда приходит новая запись с тем же значением
  Тогда кандидата с удалённой записью нет
  # tombstonedRecordDoesNotMatchLaterRecord

Сценарий 8 (лимит): популярный признак
  Дано у hostname уже 51 другая запись (MAX_MATCHES + 1)
  Тогда кандидаты по нему не создаются, пишется WARN без значения
  # popularFeatureAboveLimitCreatesNoCandidates

Сценарий 9 (отказ хранилища): SQL-ошибка
  Тогда rollback и IdentityStoreException("record failed"), вызывающий переводит событие в RETRYING / IDENTITY_CANDIDATE_FAILED
  # (теста на уровне модуля нет; поведение вызывающего — EventProcessorTest 🟡)

Сценарий 10 (чистые функции): extractor
  # FeatureExtractorTest: featuresPerLabel, ownerComesFromOwnedByWithGidFromResolvedMap, noOwnerWithoutOwnedByOrWithoutTeamGid,
  # extractorDoesNotChangeGids, noUpsertNodeOfKeyMeansNoFeatures, environmentAndDeploymentGiveNoFeatures,
  # ownerIsNotStrongAndScoreIsCappedSum, labelFamilyGroupsComputeInstanceSubtypes; FeatureNormalizerTest (7 тестов)
```

## Трассировка
`IR/FeatureExtractor.java`, `IR/FeatureNormalizer.java`, `IR/FeatureKind.java`, `IR/Feature.java`, `IR/LabelFamily.java`, `IR/IdentityCandidates.java`, `IR/IdentityCandidate.java`, `IR/PostgresIdentityCandidates.java`, `MIG/V4__identity_candidate.sql`. Тесты: `FeatureExtractorTest`, `FeatureNormalizerTest` (модуль); `IT/IdentityCandidateIT` (`platform/integration-tests`) 🟢.
