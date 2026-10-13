# 0032. Dev-зависимости без исправления: точечный override вместо подавления алерта

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** решение владельца 2026-10-13 (вариант C); UNLOCKER-277; Dependabot #12 (GHSA-hp3w-g68c-fv3c / CVE-2026-97058); расширяет [ADR 0027](0027-frontend-react-typescript-webpack.md)
- **Кто принял:** архитектор по решению владельца

## Контекст

Dependabot #12: `sprintf-js` ≤ 1.1.3, DoS через неограниченную точность; исправленной версии нет. Пакет только в dev-зависимостях `access/admin-ui` (scope `development`), в runtime-бандл консоли не входит. Путь: `jest 29.7.0` → `@jest/transform` → `babel-plugin-istanbul 6.1.1` → `@istanbuljs/load-nyc-config 1.1.0` → `js-yaml 3.15.2` → `argparse 1.0.10` → `sprintf-js 1.0.3`. `argparse` нужен только CLI `js-yaml`, а `load-nyc-config` вызывает библиотечный `yaml.load`, поэтому код недостижим. Обновление Jest не помогает: даже `babel-plugin-istanbul 8.0.0` тянет `load-nyc-config 1.1.0` с `js-yaml ^3`.

## Решение

В `access/admin-ui/package.json` задан `overrides`: `@istanbuljs/load-nyc-config` → `js-yaml 4.3.2` (последняя 4.x, закрывает GHSA-2883-xcg3-v3hh, GHSA-5p4m-2wfm-xmqj, GHSA-52cp-r559-cp3m). Он зависит от `argparse ^2`, у которого нет `sprintf-js`; цепочка уходит из lockfile. Ветка 5.x не берётся: шире изменения API и свои открытые advisory. Версии Jest и прочих прямых зависимостей не меняются.

Рассмотренные варианты:
- **Подавление алерта** — скрывает проблему, не убирает пакет.
- **Override `argparse`** — `argparse@2` несовместим по API с `js-yaml@3`.
- **Ожидание** — исправленной версии `sprintf-js` нет.
- **Смена тест-раннера** — несоразмерная цена ради недостижимого кода.

## Последствия

- `npm ls` показывает `js-yaml@4.3.2 overridden`/invalid для диапазона `^3.13.1`; это ожидаемо.
- `load-nyc-config` использует только `yaml.load`; в js-yaml 4 он безопасен по умолчанию, `safeLoad` не вызывается. Проверено прогоном `jest --coverage` с временным `.nycrc.yaml`.
- **Условие снятия:** `@istanbuljs/load-nyc-config` перешёл на `js-yaml ≥ 4`; тогда override удаляется.
- **Откат:** Revert PR.
- **Затронутый код:** `access/admin-ui/package.json`, `package-lock.json`.
