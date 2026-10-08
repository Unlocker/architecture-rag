# 0027. Фронтенд: React + TypeScript + Webpack, сборка отдельным Maven-модулем

- **Статус:** Принято
- **Дата:** 2026-10-08
- **Источник:** постановка владельца в UNLOCKER-234 («ReactJS + Typescript + Webpack, сборка отдельным maven модулем»); UNLOCKER-240
- **Кто принял:** владелец

## Контекст

Админ-консоль ([0026](0026-admin-console-readonly-loopback.md)) — первый UI проекта. Стек [0015](0015-java21-spring-maven-stack.md) покрывает только Java; нужно решить, чем и как собирать фронтенд, не ломая правило «`./mvnw verify` собирает всё».

## Решение

- Модуль `access/admin-ui` с packaging `jar`; бандл Webpack попадает в `META-INF/resources`, статику отдаёт `access/admin-console`.
- Стек: React, TypeScript, Webpack (версии зафиксированы в `access/admin-ui/package.json`).
- `frontend-maven-plugin` сам ставит Node и npm; версии заданы свойствами `node.version` и `npm.version` в `access/admin-ui/pom.xml`, версия плагина — в корневом `pom.xml` (`frontend-maven-plugin.version`). Установленный на машине Node не нужен.
- `npm ci` (фаза `generate-resources`), `npm test` (`test`) и `npm run build` (`prepare-package`) идут в фазах Maven; `-DskipTests` пропускает `npm test`.
- `package-lock.json` коммитится; зависимости в `package.json` закреплены точными версиями.
- Политика npm-зависимостей: только согласованные; новая зависимость или повышение версии — по решению архитектора.
- Тесты — Jest и Testing Library (`jest`, `ts-jest`, `jest-environment-jsdom`, `@testing-library/react`).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| React + TypeScript + Webpack в Maven-модуле | Один `./mvnw verify`, воспроизводимые версии Node/npm, один артефакт | Node-экосистема в Java-репозитории | Выбран |
| Отдельная npm-сборка вне Maven | Привычный фронтенд-цикл | Второй шаг сборки и CI, бандл нужно доставлять в jar отдельно, зависимость от Node на машине | Отклонён |
| SSR и тяжёлые фреймворки | Богатые возможности | Консоль — внутренний read-only SPA; SSR и фреймворк не нужны, растёт поверхность зависимостей | Отклонён |

## Последствия

- **Положительные:** сборка и тесты фронтенда входят в обычный `verify`; версии Node/npm и зависимости закреплены.
- **Отрицательные / ограничения:**
  - Node в CI: `setup-node` не нужен, плагин скачивает Node и npm в `target/`; кэш для них в `.github/workflows/ci.yml` не настроен (кэшируется только Maven), поэтому каждый прогон скачивает их и зависимости npm заново. Кэш — возможное улучшение.
  - Нужна фронтенд-компетенция в команде.
  - Зависимости npm надо обновлять и проверять на уязвимости; процесса пока нет.
- **Затронутый код:** `access/admin-ui`, корневой `pom.xml` (`pluginManagement`).

## Связанные ADR

Расширяет [0015](0015-java21-spring-maven-stack.md); [0026](0026-admin-console-readonly-loopback.md)
