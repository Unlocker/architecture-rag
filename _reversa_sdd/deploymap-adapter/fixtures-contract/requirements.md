# Use case: fixtures-contract (контракт fixtures deploymap-poc-1)

Модульный контекст: [../requirements.md](../requirements.md), формат — [../contracts.md](../contracts.md).

## Visão Geral
Набор JSON-fixtures в `adapters/deploymap-adapter/src/test/resources/deploymap` с тестами `DeployMapFixturesContractTest`. Держит согласованность временного формата `deploymap-poc-1` с заглушками SCM/EAM/deploy map и описывает сценарии для нормализатора (baseline, unknown-repository, system-mismatch, invalid-commit, commit-change, legacy-poc-0). Реального источника нет, ключи аннотаций — решение архитектора UNLOCKER-248 (`README.md:4-5`) 🟢.

## Regras de Negócio
- Все poc-1-fixtures: `payload.format = "deploymap-poc-1"`, `chartMeta` с `name`, `version`, `appVersion`; `chartMeta.name == chart`, `chartMeta.version == chartVersion`; `annotations` — ровно 3 ключа `archrag.io/*` 🟢.
- `baseline`: commit 40 hex; `GitUrls.normalize(repo-url)` == `url` репозитория `repo-payments-api` в SCM; `eam-system-id` == `id` EAM-системы сервиса (поиск по `eam_id = systemCode`); commit == `StubSources.PAYMENTS_API_COMMIT`; payload равен payload seed `dep-payments-api-prod` 🟢.
- `unknown-repository`: нормализованный URL ≠ URL любого репозитория SCM; commit валиден 🟢.
- `system-mismatch`: репозиторий тот же, `eam-system-id` ≠ системе сервиса и == `EamApiSeed.SYSTEM_LEDGER` (5102) 🟢.
- `invalid-commit`: commit не соответствует `[0-9a-f]{40}` 🟢.
- `commit-change`: v2 с тем же `sourceId`, `sourceVersion` больше, commit валиден и отличается 🟢.
- `legacy-poc-0`: `format = deploymap-poc-0`, нет `chartMeta`, есть `chart`, `chartVersion`, `service`, `environment`, `hosts` 🟢.
- Тест не вызывает нормализатор; реакция на негативные fixtures проверяется вне модуля 🟡 (Q-04).

## Requisitos Funcionais
| ID | Требование | Приоритет | Критерий (тест) |
|----|-----------|-----------|-----------------|
| RF-01 | Структура poc-1 (chartMeta + 3 аннотации) | Must | `everyPoc1FixtureHasChartMetaWithThreeAnnotations` |
| RF-02 | baseline согласован с SCM/EAM seed | Must | `baselineIsConsistentWithScmAndEamSeed` |
| RF-03 | baseline == seed заглушки | Must | `baselineMatchesStubSeed` |
| RF-04 | Негативные сценарии отличаются от baseline ровно одним признаком | Should | `unknownRepositoryDoesNotMatchAnyScmRepository`, `systemMismatchKeepsRepositoryButChangesSystem`, `invalidCommitIsNot40Hex` |
| RF-05 | Смена commit поднимает `sourceVersion` при том же `sourceId` | Must | `commitChangeKeepsSourceIdAndRaisesVersion` |
| RF-06 | poc-0 остаётся валидным без `chartMeta` | Should | `legacyPoc0HasNoChartMeta` |
| RF-07 | Заглушка deploy map принадлежит `SourceSystem.DEPLOY_MAP` | Could | `stubSystemIsDeployMap` |

## Critérios de Aceitação
```gherkin
Дано fixture baseline/deployments.json
Когда читаются аннотации
Тогда commit совпадает с [0-9a-f]{40}, нормализованный repo-url равен url репозитория SCM, eam-system-id равен id системы сервиса

Дано fixture invalid-commit/deployments.json
Когда читается archrag.io/git-commit
Тогда значение "not-a-commit" не соответствует [0-9a-f]{40}

Дано fixture unknown-repository
Когда URL нормализуется GitUrls.normalize
Тогда он не равен url репозитория repo-payments-api

Дано commit-change v1 и v2
Когда сравниваются sourceId, sourceVersion и commit
Тогда sourceId совпадает, sourceVersion растёт 1 -> 2, commit различается
```

## Rastreabilidade
`adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java:25-156` (9 тестов, `@Test` на строках 81, 96, 106, 113, 120, 128, 134, 144, 152) 🟢.
