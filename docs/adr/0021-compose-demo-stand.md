# 0021. Демо-стенд на Docker Compose вместо Kubernetes; backup Neo4j через `dump/load`

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** владелец 2026-10-03; UNLOCKER-195; [`00-vision.md`, «PoC topology»](../00-vision.md#poc-topology)
- **Кто принял:** владелец

## Контекст

Kubernetes-описание в видении — целевая production-топология. Для PoC она избыточна. Online backup доступен только в Enterprise.

## Решение

Стенд разворачивается на Docker Compose. Соответствия: CronJob → планировщик в адаптере, NetworkPolicy → раздельные сети, Vault → Docker secrets, backup → `neo4j-admin database dump/load`. Kubernetes/Helm — production delta.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Docker Compose | Просто, воспроизводимо | Нет HA | Выбран |
| Kubernetes (прежний вариант) | Целевая платформа | Объём работ, не нужно для PoC | Отложен: production delta |

## Последствия

- **Положительные:** Быстрая сборка стенда.
- **Отрицательные / ограничения:** Переход на Kubernetes — отдельная работа.
- **Затронутый код:** `platform/docker`

## Связанные ADR

[0007](0007-s3-raw-storage-seaweedfs.md)
