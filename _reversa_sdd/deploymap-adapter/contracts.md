# adapters/deploymap-adapter, контракты

Нормативный источник формата — `platform/source-stubs/.../DeployMapFormat.java`; здесь зафиксировано то, что охраняют fixtures и тесты модуля. Формат временный (🔴 Q-01).

## 1. Событие, которое выпускает адаптер для deploy map

| Поле | Значение | Evidence |
|------|----------|----------|
| `source` | `urn:corp:deploymap` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java:47-49` 🟢 |
| consumer'ы checkpoint | `deploymap-poller`, `deploymap-snapshot` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Poller.java:78-86` 🟢 |
| payload | payload объекта источника без `null`-значений плюс ключ `_completeness` (для `DELETE` payload пуст) | `EventMapper.java:35, 52-63` 🟢 |
| webhook | `POST /webhook` на адаптере; снаружи `POST /webhooks/deploymap` через nginx (`rewrite ^ /webhook`) | `platform/docker/proxy/nginx.conf:58-62` 🟢 |
| API источника | `GET /changes?cursor=&limit=`, `GET /objects/{type}/{id}` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/HttpSourceConnector.java:25-26, 56, 72` 🟢 |

Подробности подписи webhook, ответов и повторов — спецификация `adapter-core`.

## 2. Формат записи источника (элемент массива fixtures)

`{ "sourceId": string, "sourceVersion": integer, "payload": object }` (`deploymap/README.md:3-4`) 🟢. `sourceType` в fixtures не хранится: для `DEPLOYMENT` это `DeployMapFormat.DEPLOYMENT`, для `ENVIRONMENT` — `DeployMapFormat.ENVIRONMENT` (`DeployMapFormat.java:44-45`) 🟢.

### Типы объектов

| `sourceType` | Поля payload | Источник |
|--------------|--------------|----------|
| `ENVIRONMENT` | `format` (`deploymap-poc-0`), `name` | seed `prod` → `Production`, `StubSources.java:92-93` 🟢 |
| `DEPLOYMENT` (poc-0) | `format`=`deploymap-poc-0`, `name`, `service`, `environment`, `chart`, `chartVersion`, `hosts[]` | `legacy-poc-0/deployments.json`, `DeployMapFixturesContractTest.legacyPoc0HasNoChartMeta` 🟢 |
| `DEPLOYMENT` (poc-1) | то же, `format`=`deploymap-poc-1`, плюс `chartMeta{name, version, appVersion, annotations{}}` | `baseline/deployments.json`; `DeployMapFormat.java:16-22` 🟢 |

Инварианты poc-1 (проверяет `everyPoc1FixtureHasChartMetaWithThreeAnnotations`, `DeployMapFixturesContractTest.java:81-94`): `chartMeta.name == payload.chart`; `chartMeta.version == payload.chartVersion`; `annotations` содержит ровно три ключа ниже 🟢.

### Ключи аннотаций (`DeployMapFormat.java:35-42`)

| Ключ | Значение | Правило |
|------|----------|---------|
| `archrag.io/eam-system-id` | строка цифр, например `"5101"` | `id` объекта EAM, не `eam_id` |
| `archrag.io/git-repo-url` | URL репозитория, допустим ssh (`git@git.example.org:pay/payments-api.git`) | после `GitUrls.normalize` равен `url` репозитория SCM (`https://git.example.org/pay/payments-api`) |
| `archrag.io/git-commit` | 40 hex нижнего регистра | регэксп `[0-9a-f]{40}` |

## 3. Fixtures и ожидания

| Каталог | `sourceVersion` | `eam-system-id` | repo-url | commit | Назначение |
|---------|-----------------|-----------------|----------|--------|------------|
| `baseline/deployments.json` | 1 | `5101` | `git@git.example.org:pay/payments-api.git` | `9fceb02d0ae598e95dc970b74767f19372d61af8` | позитив; совпадает с `StubSources.deployMap` |
| `legacy-poc-0/` | 1 | - | - | - | формат без `chartMeta` |
| `unknown-repository/` | 1 | `5101` | `https://git.example.org/pay/unknown-repo.git` | `9fce…1af8` | репозитория нет в SCM |
| `system-mismatch/` | 1 | `5102` (`EamApiSeed.SYSTEM_LEDGER`) | как baseline | как baseline | система ≠ системе сервиса |
| `invalid-commit/` | 1 | `5101` | как baseline | `not-a-commit` | commit не 40 hex |
| `commit-change/deployments-v1.json` | 1 | `5101` | как baseline | `9fce…1af8`, `chartVersion` 1.4.2 | исходное состояние |
| `commit-change/deployments-v2.json` | 2 | `5101` | как baseline | `1b4f0e9851971998e732078544c96b36c3d01ced`, `chartVersion` 1.4.3 | тот же `sourceId` `dep-payments-api-prod`, новый commit |

Все fixtures — один элемент с `sourceId = dep-payments-api-prod`, `service = svc-payments-api`, `environment = prod`, `hosts = [vm-pay-01]` 🟢.

Seed-опоры (вне модуля): репозиторий SCM `repo-payments-api` (`https://git.example.org/pay/payments-api`), сервис `svc-payments-api` с `systemCode = EAM-1042`, EAM `id=5101` (`EamApiSeed.SYSTEM_PAYMENTS_CORE`), 5102 — Ledger (`deploymap/README.md:7-9`) 🟢.
