# Индекс specs (SDD, уровень «Completo»)

> Reversa Writer, R.6a + R.6b (UNLOCKER-271, UNLOCKER-272). Язык — русский, термины кода как в коде. Layout `hybrid`: папка модуля (`requirements.md`, `design.md`, `tasks.md`, `contracts.md`, `decisions.md`, `questions.md`) и вложенные папки use case (`requirements.md`, `design.md`, `tasks.md`). Ссылки на код — полные пути от корня репозитория с `:строка`. Метки уверенности: 🟢 подтверждено кодом, 🟡 выведено, 🔴 пробел.

HTML-сайт по этим specs: [`_reversa_docs/index.html`](_reversa_docs/index.html) (работает офлайн через `file://`, см. `README.md` в корне репозитория).

## Единицы (21)

| Модуль | Спецификация | Файлов | Use case | Открытые вопросы |
|--------|--------------|-------:|----------|--------------------------|
| `contracts/canonical-model` | [`canonical-model/`](canonical-model/requirements.md) | 18 | `authority-lookup`, `git-url-normalization`, `node-model-invariants`, `relation-command-validation` | [questions](canonical-model/questions.md) |
| `contracts/event-schemas` | [`event-schemas/`](event-schemas/requirements.md) | 17 | `journal-append-dedup-dlq`, `journal-keyset-read`, `processing-status-lifecycle`, `source-version-compare` | [questions](event-schemas/questions.md) |
| `contracts/source-spi` | [`source-spi/`](source-spi/requirements.md) | 15 | `fetch-by-id`, `fetch-changes`, `verify-webhook-signature` | [questions](source-spi/questions.md) |
| `adapters/adapter-core` | [`adapter-core/`](adapter-core/requirements.md) | 18 | `full-snapshot`, `incremental-poll`, `receive-webhook`, `source-fetch-retry` | [questions](adapter-core/questions.md) |
| `adapters/eam-adapter` | [`eam-adapter/`](eam-adapter/requirements.md) | 15 | `composite-sync`, `eam-api-request`, `fetch-changes` | [questions](eam-adapter/questions.md) |
| `adapters/scm-adapter` | [`scm-adapter/`](scm-adapter/requirements.md) | 10 | `create-adapter`, `fetch-scm-changes` | [questions](scm-adapter/questions.md) |
| `adapters/asset-adapter` | [`asset-adapter/`](asset-adapter/requirements.md) | 10 | `assemble-adapter`, `source-contract` | [questions](asset-adapter/questions.md) |
| `adapters/deploymap-adapter` | [`deploymap-adapter/`](deploymap-adapter/requirements.md) | 11 | `assemble-adapter`, `fixtures-contract` | [questions](deploymap-adapter/questions.md) |
| `ingestion/event-journal` | [`event-journal/`](event-journal/requirements.md) | 17 | `append-event`, `raw-payload-store`, `read-journal`, `transition-event` | [questions](event-journal/questions.md) |
| `ingestion/normalizer` | [`normalizer/`](normalizer/requirements.md) | 18 | `bind-deployment`, `map-eam-objects`, `normalize-event`, `resolve-references` | [questions](normalizer/questions.md) |
| `ingestion/identity-resolution` | [`identity-resolution/`](identity-resolution/requirements.md) | 15 | `candidate-matching`, `gid-mapping-crosswalk`, `source-conflicts` | [questions](identity-resolution/questions.md) |
| `ingestion/graph-projector` | [`graph-projector/`](graph-projector/requirements.md) | 18 | `deferred-relations`, `process-event`, `project-request`, `reconcile-missing-set` | [questions](graph-projector/questions.md) |
| `ingestion/ingestion-service` | [`ingestion-service/`](ingestion-service/requirements.md) | 18 | `dispatch-journal`, `load-crosswalk`, `reconcile-source`, `replay-rebuild` | [questions](ingestion-service/questions.md) |
| `access/graph-query-core` | [`graph-query-core/`](graph-query-core/requirements.md) | 21 | `asset-search`, `budget-limits`, `execute-template`, `template-registry`, `trace-and-footprint` | [questions](graph-query-core/questions.md) |
| `access/mcp-server` | [`mcp-server/`](mcp-server/requirements.md) | 21 | `authorize-tool-call`, `explain-provenance`, `find-runtime-footprint`, `search-get-asset`, `trace-dependencies` | [questions](mcp-server/questions.md) |
| `access/admin-console` | [`admin-console/`](admin-console/requirements.md) | 18 | `auth-and-spa`, `graph-browse`, `identity-audit`, `sync-journal` | [questions](admin-console/questions.md) |
| `access/admin-ui` | [`admin-ui/`](admin-ui/requirements.md) | 21 | `build-bundle`, `graph-explorer`, `identity-audit`, `oidc-login`, `sync-monitoring` | [questions](admin-ui/questions.md) |
| `platform/source-stubs` | [`source-stubs/`](source-stubs/requirements.md) | 18 | `eam-api-stub`, `signed-webhook`, `stub-source-http`, `stub-source-journal` | [questions](source-stubs/questions.md) |
| `platform/demo-launcher` | [`demo-launcher/`](demo-launcher/requirements.md) | 15 | `run-adapter`, `run-eam-api-stub`, `run-source-stub` | [questions](demo-launcher/questions.md) |
| `platform/integration-tests` | [`integration-tests/`](integration-tests/requirements.md) | 21 | `acceptance-mcp-f1-f4`, `admin-console-security`, `projection-identity-conflicts`, `query-core-read`, `sync-reconcile` | [questions](integration-tests/questions.md) |
| `platform/docker` | [`docker/`](docker/requirements.md) | 21 | `backup-restore`, `keycloak-oidc`, `network-secrets`, `observability-measure`, `stand-startup` | [questions](docker/questions.md) |

Всего 356 файлов в папках единиц, 79 папок use case. Формат `questions.md` в R.6a и R.6b немного различается (таблица Q-/R- или список); 🔴 — требуют решения владельца, 🟡 — риск принят или нет теста.

## Глобальные артефакты

| Артефакт | Содержание |
|----------|------------|
| [`traceability/code-spec-matrix.md`](traceability/code-spec-matrix.md) | файл кода → единица specs → покрытие (генерируется по `git ls-files`) |
| [`traceability/spec-impact-matrix.md`](traceability/spec-impact-matrix.md) | связности, где изменение одной спеки требует правки другой (R.5) |
| [`openapi/`](openapi/) | `admin-console.yaml`, `ingestion-admin.yaml`, `mcp-server.yaml`, `adapter-http.yaml` (только существующие ручки, `x-source` с `файл:строка`) |
| [`user-stories/`](user-stories/README.md) | 5 файлов историй по акторам |

HTTP-ручки заглушек источников (`/changes`, `/objects/{type}/{id}`, `/api/**` eam-api) и управляющие ручки demo-launcher (`/control/*`, `/health`) — тестовая инфраструктура; описаны в [`source-stubs/contracts.md`](source-stubs/contracts.md) и [`demo-launcher/contracts.md`](demo-launcher/contracts.md), в OpenAPI не вынесены.

## Входные артефакты прошлых этапов

[`inventory.md`](inventory.md), [`dependencies.md`](dependencies.md), [`soul.md`](soul.md), [`code-analysis.md`](code-analysis.md), [`data-dictionary.md`](data-dictionary.md), [`flowcharts/`](flowcharts/), [`database/`](database/), [`domain.md`](domain.md), [`state-machines.md`](state-machines.md), [`permissions.md`](permissions.md), [`detective-gaps.md`](detective-gaps.md), [`adrs/`](adrs/), [`architecture.md`](architecture.md), [`c4-context.md`](c4-context.md), [`c4-containers.md`](c4-containers.md), [`c4-components.md`](c4-components.md), [`erd-complete.md`](erd-complete.md), [`integrations.md`](integrations.md), [`tech-debt.md`](tech-debt.md).

## Как читать

1. Обзор: [`architecture.md`](architecture.md), [`c4-containers.md`](c4-containers.md).
2. Модуль: `requirements.md` → `design.md` → `contracts.md`; сценарии — во вложенных папках.
3. Открытые вопросы владельцу: `questions.md` модулей и [`detective-gaps.md`](detective-gaps.md); решения: `decisions.md`.
