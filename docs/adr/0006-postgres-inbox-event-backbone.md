# 0006. Event backbone PoC — PostgreSQL inbox за `EventJournal`/`CanonicalEventPublisher`

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** [`00-vision.md`, «Выбор event backbone»](../00-vision.md#выбор-event-backbone); [`00-vision.md`, «Варианты исполнения»](../00-vision.md#варианты-исполнения)
- **Кто принял:** архитектор, принято владельцем

## Контекст

Нужны идемпотентность, replay, checkpoints и DLQ. Kafka даёт это, но увеличивает инфраструктуру PoC.

## Решение

Вариант A: адаптер → PostgreSQL inbox → worker → Neo4j. Граница — интерфейсы `EventJournal` и `CanonicalEventPublisher`, чтобы заменить PostgreSQL на Kafka без правок адаптеров и projector. Kafka — production (вариант B, вне PoC).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A: PostgreSQL inbox + workers | Транзакционная очередь, мало инфраструктуры | Ниже throughput, сложнее fan-out | Выбран |
| B: Kafka (Strimzi) | Replay, backpressure, масштабирование | Инфраструктура, расчёт HA | Отклонён для PoC, production |
| Адаптер пишет в Neo4j напрямую | Минимум компонентов | Связанность, сложный replay | Отклонён |

## Последствия

- **Положительные:** Меньше компонентов на стенде; проверяются те же гарантии.
- **Отрицательные / ограничения:** Throughput ограничен PostgreSQL; переход на Kafka — отдельная работа (production gap list).
- **Затронутый код:** `contracts/event-schemas`, `ingestion/*`

## Связанные ADR

[0001](0001-neo4j-derived-read-model.md), [0007](0007-s3-raw-storage-seaweedfs.md)
