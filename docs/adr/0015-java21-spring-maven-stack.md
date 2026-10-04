# 0015. Стек: Java 21, Spring Boot 4.x, Spring AI 2.x, Maven multi-module, GitHub Actions

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** владелец (решение 10); UNLOCKER-156; UNLOCKER-157; [PR #2](https://github.com/Unlocker/architecture-rag/pull/2); [PR #4](https://github.com/Unlocker/architecture-rag/pull/4); [`00-vision.md`, «Java-модули»](../00-vision.md#java-модули)
- **Кто принял:** владелец

## Контекст

Нужен единый стек для адаптеров, projector и MCP-сервера, поддерживаемый командой.

## Решение

Java 21, Spring Boot 4.x, Spring AI 2.x, Maven multi-module (версии только через BOM/`dependencyManagement` корня), CI на GitHub Actions. Новые библиотеки добавляются только по задаче.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Java 21 + Spring + Maven | Знакомый стек, Spring AI MCP starter | — | Выбран |
| Gradle | Гибкость сборки | Лишний инструмент | Отклонён |
| Kotlin/Scala | — | Вне стека команды | Отклонён |

## Последствия

- **Положительные:** Единые версии и правила для всех модулей.
- **Отрицательные / ограничения:** Spring Boot 4.x и Spring AI 2.x — новые мажорные версии, возможны несовместимости.
- **Затронутый код:** `pom.xml`, `.github/workflows`

## Связанные ADR

[0016](0016-archrag-coordinates.md)
