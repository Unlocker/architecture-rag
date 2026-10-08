# 0026. Админ-консоль — отдельный read-only сервис, доступ только с loopback

- **Статус:** Принято
- **Дата:** 2026-10-08
- **Источник:** UNLOCKER-234 (E6), UNLOCKER-239 (U.5); план архитектора в задаче UNLOCKER-239
- **Кто принял:** архитектор; владелец может пересмотреть

## Контекст

Оператору нужен обзор графа и состояния синхронизации. Изменяющие операции уже вынесены в admin REST ingestion ([0013](0013-operator-ops-via-admin-endpoint.md)); смешивать с ними UI нельзя. Консоль работает с тем же PostgreSQL и Neo4j, но не должна получать права записи.

## Решение

- Консоль — отдельный сервис `access/admin-console` (+ статический бандл `access/admin-ui`), только чтение: граф под reader-учёткой Neo4j (`executeRead`, [0005](0005-readonly-mcp-by-application.md)), журнал под ролью PostgreSQL `archrag_console_ro` (только `SELECT`, миграция V6). Writer-секретов в контейнере нет.
- Доступ к API: OIDC resource server со scope `architecture.admin` и собственным audience `urn:archrag:console`. Вход — публичный клиент Keycloak `archrag-admin-console`, Authorization Code + PKCE S256, без direct grants. Scope `architecture.admin` у клиента optional: UI запрашивает его явно.
- На стенде консоль и Keycloak публикуются только на `127.0.0.1` (`ARCHRAG_CONSOLE_PORT`, `ARCHRAG_KEYCLOAK_PORT`); через reverse proxy консоль не публикуется, в сети `edge` её нет. Браузер обращается к Keycloak как `http://keycloak:8080` (запись в `hosts`), потому что `iss` токенов фиксирован.
- Scope `architecture.admin` выдаёт и audience записывающего admin REST ingestion. Чтобы токен консоли не проходил в него, admin REST дополнительно проверяет claim `azp` по списку `archrag.admin.allowed-clients` (по умолчанию `archrag-demo`); чужой или отсутствующий `azp` — `401`.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Отдельный read-only сервис, loopback | Нет write-пути, минимальная поверхность | Нужен hosts-алиас для Keycloak | Выбран |
| Консоль внутри ingestion | Меньше сервисов | UI получает writer-права | Отклонён |
| Публикация через reverse proxy | Единая точка входа | Консоль наружу, расширение внешней поверхности | Отклонён |
| Перенос audience-mapper `admin` на клиент `archrag-demo` | Нет проверки `azp` | read-токены `archrag-demo` получили бы admin audience, E5-смоук «read → 401» стал бы 403 | Отклонён |

## Последствия

- **Положительные:** граница «консоль только читает» держится секретами, сетями и `azp`; проверяется `platform/docker/console-smoke.sh`.
- **Отрицательные / ограничения:**
  - Порт Keycloak на loopback открывает и его master-админку на `127.0.0.1` (как Grafana).
  - Scope `architecture.admin` может запросить любой пользователь realm через консольный клиент: ролевого ограничения нет (оно задело бы service account `archrag-demo`). На стенде есть только демо-администратор; пункт в [gap list](../05-production-gap-list.md) (2.7).
- **Затронутый код:** `access/admin-console`, `ingestion/ingestion-service` (`AuthorizedPartyValidator`), `platform/docker`.

## Связанные ADR

[0005](0005-readonly-mcp-by-application.md), [0013](0013-operator-ops-via-admin-endpoint.md), [0018](0018-domain-tools-not-arbitrary-cypher.md) (граф читается только фиксированными запросами)
