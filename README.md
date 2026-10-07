# Arch RAG

AI-native витрина архитектурных активов: мастер-системы остаются владельцами данных, адаптеры синхронизируют их через PostgreSQL inbox в производный read-only граф Neo4j, а MCP-сервер даёт агентам ограниченный набор предметных операций (без произвольного Cypher). В PoC входят вертикальный срез из трёх мастер-систем (заглушек), идемпотентная синхронизация с replay/rebuild, пять MCP-tools (`search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies`, `explain_provenance`) и демо-стенд. Полный список критериев готовности — в разделе «Критерии готовности PoC» [видения](docs/00-vision.md).

## Документация

| Документ | О чём |
|---|---|
| [docs/00-vision.md](docs/00-vision.md) | Видение и решения владельца для PoC |
| [docs/01-development-order.md](docs/01-development-order.md) | Порядок разработки, ветвление, маршрут задач |
| [docs/02-data-model.md](docs/02-data-model.md) | Модель данных |
| [docs/03-interfaces.md](docs/03-interfaces.md) | Интерфейсы |
| [docs/adr/README.md](docs/adr/README.md) | Реестр ADR |
| [docs/adr/0000-template.md](docs/adr/0000-template.md) | Шаблон нового ADR |
| [ingestion/ingestion-service/README.md](ingestion/ingestion-service/README.md) | Админский эндпоинт ингеста и переменные окружения |

Открытые вопросы, ждущие решения владельца (ADR со статусом «Предложено»):
[ADR 0024](docs/adr/0024-open-source-id-format.md), [ADR 0025](docs/adr/0025-open-deployment-env-index.md).

## Структура репозитория

Maven multi-module, Java 21. Версии задаются в корневом [pom.xml](pom.xml).

| Каталог | Назначение |
|---|---|
| `contracts/` | Общие контракты: каноническая модель, схемы событий, SPI источников (`canonical-model`, `event-schemas`, `source-spi`) |
| `adapters/` | Адаптеры мастер-систем: `adapter-core`, `eam-adapter`, `scm-adapter`, `asset-adapter`, `deploymap-adapter` |
| `ingestion/` | Контур записи: `event-journal`, `normalizer`, `identity-resolution`, `graph-projector`, `ingestion-service` |
| `access/` | Контур чтения: `graph-query-core` и `mcp-server` |
| `platform/` | Сквозные модули: `integration-tests`, `source-stubs` |

## Сборка и тесты

Нужны JDK 21 и Maven Wrapper из репозитория.

```bash
# сборка и unit-тесты
./mvnw -B -ntp verify

# то же вместе с интеграционными тестами (*IT)
./mvnw -B -ntp verify -Pintegration-tests
```

Профиль `integration-tests` использует Testcontainers, поэтому требует запущенный Docker. Эти же команды выполняет [CI](.github/workflows/ci.yml).

## Демо-стенд

Инструкция по запуску появится после вливания E5 (UNLOCKER-195).

## Как вносить изменения

Ветвление, PR и вливание описаны в разделе [«Ветвление и вливание»](docs/01-development-order.md#ветвление-и-вливание) документа о порядке разработки.
