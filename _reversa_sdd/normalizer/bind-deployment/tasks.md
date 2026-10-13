# bind-deployment — задачи

## Пререквизиты
- [ ] `CommandSink`, `Payload`, `DeploymentLookup` (T-02…T-04 в [`../tasks.md`](../tasks.md)); `ScmMapper.SERVICE`, `CmdbMapper.COMPUTE_INSTANCE`.
- [ ] `GitUrls`, `LookupKeys`, `Deployment`, `Environment`, `EnvironmentClass` из `canonical-model`.

## Задачи
- [ ] T-DM-01 `ENVIRONMENT`: код = `sourceId`, класс `class` ?? код, словарь классов.
  - Origin: `NZ/DeployMapMapper.java:84-91,244-252`
  - Критерий готовности: AC-DM-01.
  - Уверенность: 🟢
- [ ] T-DM-02 `chartMeta(p)`: `format`, обязательность `chartMeta`/аннотаций, валидация commit/system id/URL, сообщения без значений.
  - Origin: `NZ/DeployMapMapper.java:138-190`
  - Критерий готовности: AC-DM-03, AC-DM-04.
  - Уверенность: 🟢
- [ ] T-DM-03 Узел `Deployment` из payload и `ChartMeta`; `observedAt = event.time()`.
  - Origin: `NZ/DeployMapMapper.java:92-105`
  - Критерий готовности: AC-DM-02 (равенство `Deployment("dep-1","payments-api","1.4.2",null,COMMIT,URL,5101L,T)`), AC-DM-10.
  - Уверенность: 🟢
- [ ] T-DM-04 `bindToRepository`: `DEPLOYED_FROM{commit}` + выбор сервиса (4 ветки) + `DEPLOYMENT_SERVICE_UNRESOLVED`.
  - Origin: `NZ/DeployMapMapper.java:193-237`
  - Критерий готовности: AC-DM-05…AC-DM-09.
  - Уверенность: 🟢
- [ ] T-DM-05 `IN_ENVIRONMENT`, `RUNS_ON`, `validity` из `validFrom`.
  - Origin: `NZ/DeployMapMapper.java:112-132,240-242`
  - Критерий готовности: AC-DM-11.
  - Уверенность: 🟢

## Тесты
- [ ] TT-DM-01 `DeployMapMapperTest` — 16 тестов (poc-1 позитив, карантин, неизвестный репозиторий, выбор сервиса).
- [ ] TT-DM-02 `NormalizerTest#deployMapEnvironmentInfersClassFromCode`, `#deployMapDeploymentMapsServiceEnvironmentAndHosts`, `#structuralRelationsCarryValidityFromSource`, `#deploymentWithUnknownReferencesYieldsUnresolvedForEach`, `#absentNameOfEnvironmentAndDeploymentIsQuarantinedNotDefaulted`.
- [ ] TT-DM-03 (нет в коде 🟡) Тест класса окружения с неизвестным значением (`INVALID_PAYLOAD "cannot determine environment class"`) и числового значения аннотации (`Integer` вместо строки → `INVALID_PAYLOAD` через `Payload.optional`).

## Порядок
T-DM-01 → T-DM-02 → T-DM-03 → T-DM-05 → T-DM-04.

## Открытые
🟡 Q-09, Q-10, Q-15 ([`../questions.md`](../questions.md)).
