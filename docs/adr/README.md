# Реестр архитектурных решений (ADR)

Ключевые решения прототипа Arch RAG: что решили, почему и откуда это решение взято. Формат описан в [шаблоне](0000-template.md).

## Правила

- **Один файл — одно решение:** `NNNN-<slug>.md`. Номера идут по порядку и не переиспользуются.
- **Статусы:**
  - `Предложено` — ждёт решения владельца;
  - `Принято`;
  - `Заменено NNNN` — решение пересмотрено, действует указанный ADR;
  - `Отклонено`.
- **Принятые ADR не переписываются.** Если решение изменилось, заводится новый ADR, а у старого меняется статус.
- **У каждого ADR есть источник:** задача или комментарий Multica, раздел [видения](../00-vision.md) или PR.
- **Открытые вопросы** оформляются как ADR со статусом `Предложено` и пометкой «ждёт решения владельца». Ответ за владельца в ADR не пишется.

## Индекс

| № | Решение | Статус | Дата | Источник |
|---|---|---|---|---|
| [0001](0001-neo4j-derived-read-model.md) | Neo4j — производная read-only витрина, мастер-системы остаются system of record | Принято | 2026-10-03 | [`00-vision.md`, «Резюме решения»](../00-vision.md#резюме-решения); [`00-vision.md`, «Целевая архитектура»](../00-vision.md#целевая-архитектура) |
| [0002](0002-four-contours.md) | Система делится на четыре независимых контура | Принято | 2026-10-03 | [`00-vision.md`, «Резюме решения»](../00-vision.md#резюме-решения) |
| [0003](0003-gid-and-source-record-identity.md) | Каноническая идентичность: `gid: UUID` и `SourceRecord {source, sourceType, sourceId}` | Принято | 2026-10-03 | владелец 2026-10-03 (решение 5); UNLOCKER-159; [`00-vision.md`, «Идентичность узлов»](../00-vision.md#идентичность-узлов) |
| [0004](0004-neo4j-community-constraints.md) | Neo4j Community: составной UNIQUE вместо NODE KEY, контроль доступа в приложении вместо RBAC | Принято | 2026-10-03 | владелец 2026-10-03 (решение 4); UNLOCKER-159; UNLOCKER-176; [`00-vision.md`, «Constraints и indexes»](../00-vision.md#constraints-и-indexes) |
| [0005](0005-readonly-mcp-by-application.md) | Read-only MCP обеспечивается приложением: `executeRead` и раздельные учётные данные | Принято | 2026-10-03 | UNLOCKER-176; владелец 2026-10-03 (решение 4); [`00-vision.md`, «Безопасность»](../00-vision.md#безопасность); [`00-vision.md`, «Критерии готовности PoC»](../00-vision.md#критерии-готовности-poc) (п. 6) |
| [0006](0006-postgres-inbox-event-backbone.md) | Event backbone PoC — PostgreSQL inbox за `EventJournal`/`CanonicalEventPublisher` | Принято | 2026-10-03 | [`00-vision.md`, «Выбор event backbone»](../00-vision.md#выбор-event-backbone); [`00-vision.md`, «Варианты исполнения»](../00-vision.md#варианты-исполнения) |
| [0007](0007-s3-raw-storage-seaweedfs.md) | Raw storage — S3-совместимое хранилище; SeaweedFS вместо MinIO в тестах и на стенде | Принято | 2026-10-04 | UNLOCKER-202 (2026-10-04); [PR #7](https://github.com/Unlocker/architecture-rag/pull/7) |
| [0008](0008-authority-matrix-field-level.md) | Authority matrix на уровне полей и связей; мастер `DEPENDS_ON` — EAM | Принято | 2026-10-03 | владелец 2026-10-03 (решение 1); UNLOCKER-161; [`00-vision.md`, «Provenance и авторитетность»](../00-vision.md#provenance-и-авторитетность) |
| [0009](0009-deployment-from-deploymap.md) | `Deployment` берётся из deploy map и Helm charts отдельным адаптером `deploymap-adapter` | Принято | 2026-10-03 | владелец 2026-10-03 (решение 2); UNLOCKER-165; [`00-vision.md`, «Provenance и авторитетность»](../00-vision.md#provenance-и-авторитетность) |
| [0010](0010-master-systems-are-stubs.md) | Мастер-системы в PoC — заглушки | Принято | 2026-10-03 | владелец 2026-10-03 (решение 3); [`00-vision.md`, «Provenance и авторитетность»](../00-vision.md#provenance-и-авторитетность) |
| [0011](0011-namespace-cluster-modeled-not-populated.md) | `Namespace` и `KubernetesCluster` моделируются, но не наполняются | Принято | 2026-10-03 | владелец 2026-10-03 (решение 6); [`00-vision.md`, «Основные узлы»](../00-vision.md#основные-узлы) |
| [0012](0012-trace-dependencies-impact-mode.md) | `analyze_impact` — режим `trace_dependencies(mode=impact)`, а не отдельный tool | Принято | 2026-10-03 | владелец 2026-10-03 (решение 7); UNLOCKER-188; [`00-vision.md`, «Tools первого релиза»](../00-vision.md#tools-первого-релиза) |
| [0013](0013-operator-ops-via-admin-endpoint.md) | Операции оператора идут через админский эндпоинт ingestion-сервиса | Принято | 2026-10-03 | владелец 2026-10-03 (решение 8); UNLOCKER-170; [`00-vision.md`, «Сценарии адаптеров»](../00-vision.md#сценарии-адаптеров) |
| [0014](0014-sla-webhook-to-neo4j.md) | SLA webhook → Neo4j: p95 ≤ 30 с на заглушках | Принято | 2026-10-03 | решение архитектора, принятое владельцем (решение 9); UNLOCKER-171; [`00-vision.md`, «Критерии готовности PoC»](../00-vision.md#критерии-готовности-poc) (п. 2) |
| [0015](0015-java21-spring-maven-stack.md) | Стек: Java 21, Spring Boot 4.x, Spring AI 2.x, Maven multi-module, GitHub Actions | Принято | 2026-10-03 | владелец (решение 10); UNLOCKER-156; UNLOCKER-157; [PR #2](https://github.com/Unlocker/architecture-rag/pull/2); [PR #4](https://github.com/Unlocker/architecture-rag/pull/4); [`00-vision.md`, «Java-модули»](../00-vision.md#java-модули) |
| [0016](0016-archrag-coordinates.md) | Maven-координаты и пакеты `archrag` вместо `akp` | Принято | 2026-10-04 | UNLOCKER-201; [PR #6](https://github.com/Unlocker/architecture-rag/pull/6) |
| [0017](0017-mcp-streamable-http.md) | MCP-транспорт — Streamable HTTP, без legacy SSE и WebSocket | Принято | 2026-10-03 | [`00-vision.md`, «Выбор транспорта»](../00-vision.md#выбор-транспорта) |
| [0018](0018-domain-tools-not-arbitrary-cypher.md) | Агентам — предметные tools с параметризованными шаблонами, не произвольный Cypher | Принято | 2026-10-03 | [`00-vision.md`, «Почему не arbitrary Cypher»](../00-vision.md#почему-не-arbitrary-cypher) |
| [0019](0019-identity-resolution-no-auto-merge.md) | Identity resolution без автоматического объединения; deterministic mapping и crosswalk в E1 до projector | Принято | 2026-10-04 | [`00-vision.md`, «Identity resolution»](../00-vision.md#identity-resolution); владелец 2026-10-04 (вариант B); UNLOCKER-173; [PR #10](https://github.com/Unlocker/architecture-rag/pull/10); [`01-development-order.md`](../01-development-order.md) |
| [0020](0020-tombstone-instead-of-hard-delete.md) | Tombstone вместо hard delete; удаления из snapshot — только после `snapshot-complete` | Принято | 2026-10-03 | [`00-vision.md`, «Синхронизация»](../00-vision.md#синхронизация); [`00-vision.md`, «Identity resolution»](../00-vision.md#identity-resolution); UNLOCKER-168; UNLOCKER-169 |
| [0021](0021-compose-demo-stand.md) | Демо-стенд на Docker Compose вместо Kubernetes; backup Neo4j через `dump/load` | Принято | 2026-10-03 | владелец 2026-10-03; UNLOCKER-195; [`00-vision.md`, «PoC topology»](../00-vision.md#poc-topology) |
| [0022](0022-out-of-poc-scope.md) | Вне PoC: semantic retrieval, resources/prompts, `compare_environments`, `find_stale_assets`, `get_sync_status` | Принято | 2026-10-03 | [`00-vision.md`, «Критерии готовности PoC»](../00-vision.md#критерии-готовности-poc); [`00-vision.md`, «Tools первого релиза»](../00-vision.md#tools-первого-релиза); [`00-vision.md`, «Resources и prompts»](../00-vision.md#resources-и-prompts) |
| [0023](0023-branching-process.md) | Процесс: `develop → epic/<parent> → feature/<child>`; вливает архитектор, в `develop` — владелец | Принято | 2026-10-03 | владелец 2026-10-03; [`01-development-order.md`](../01-development-order.md) |
| [0024](0024-open-source-id-format.md) | Формат `sourceId` — ключ источника без префикса системы (`'1042'`, не `'EAM-1042'`) | Принято | 2026-10-10 | владелец 2026-10-10; UNLOCKER-247; [`00-vision.md`, «Идентичность узлов»](../00-vision.md#идентичность-узлов) |
| [0025](0025-open-deployment-env-index.md) | Индекс `deployment_env` удалён; окружение — связь `IN_ENVIRONMENT` → `Environment.code` | Принято | 2026-10-10 | владелец 2026-10-10; UNLOCKER-247; UNLOCKER-162; [`00-vision.md`, «Constraints и indexes»](../00-vision.md#constraints-и-indexes) |
| [0026](0026-admin-console-readonly-loopback.md) | Админ-консоль — отдельный read-only сервис, доступ только с loopback (расширяет 0013) | Принято | 2026-10-08 | владелец 2026-10-08, по предложению архитектора; UNLOCKER-234; UNLOCKER-239 |
| [0027](0027-frontend-react-typescript-webpack.md) | Фронтенд: React + TypeScript + Webpack, сборка отдельным Maven-модулем (расширяет 0015) | Принято | 2026-10-08 | владелец, постановка в UNLOCKER-234; UNLOCKER-240 |

## Открытые вопросы

Нет.
