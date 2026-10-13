# build-bundle: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §5. Решение: ADR [0027](../../../docs/adr/0027-frontend-react-typescript-webpack.md).

## Visão Geral

Сборка SPA — часть обычного `./mvnw verify`: `frontend-maven-plugin` скачивает фиксированные Node и npm в `target/`, ставит зависимости (`npm ci`), гоняет Jest и собирает Webpack-бандл в `target/classes/META-INF/resources`, откуда `admin-console` отдаёт статику (SPA fallback на `index.html`). Установленный на машине Node не нужен 🟢 (`access/admin-ui/pom.xml:18-24, 28-78`; `access/admin-ui/webpack.config.js:5-18`; `docs/adr/0027-frontend-react-typescript-webpack.md:13-18`).

## Regras de Negócio

- Версии инструментов закреплены свойствами `node.version = v22.12.0`, `npm.version = 10.9.0`; установка идёт в `${project.build.directory}` 🟢 (`access/admin-ui/pom.xml:20-21, 32`).
- Фазы: `install-node-and-npm` и `npm ci` — `generate-resources`; `npm test` — `test`; `npm run build` — `prepare-package` 🟢 (`access/admin-ui/pom.xml:35-76`).
- `-DskipTests` пропускает `npm test` через свойство `skipTests` (по умолчанию `false`) 🟢 (`access/admin-ui/pom.xml:22-23, 64`).
- Версия `frontend-maven-plugin` задаётся только в корневом `pom.xml` (`pluginManagement`, свойство `frontend-maven-plugin.version`), в модуле версии нет 🟢 (`pom.xml:37, 202-206`).
- Зависимости npm закреплены точными версиями (без `^`/`~`) и `package-lock.json`; новая зависимость или повышение версии — решение архитектора 🟢 (`access/admin-ui/package.json:11-34`; `docs/adr/0027-frontend-react-typescript-webpack.md:18-20`).
- Бандл: `[name].[contenthash].js` и `[name].[contenthash].css`, `publicPath: '/'`, `clean: true`; CSS выносится плагином `MiniCssExtractPlugin` (а не `style-loader`), чтобы не нарушить CSP без inline-стилей 🟢 (`access/admin-ui/webpack.config.js:13-18, 23, 26-30`).
- HTML не минифицируется самим Webpack (`optimization.minimizeOptions.html: false`), иначе `id=root` теряет кавычки, а Java-тесты консоли разбирают `index.html` по `id="root"` 🟢 (`access/admin-ui/webpack.config.js:31-33`).
- В production исходные карты не публикуются (`devtool: false`), в разработке — `source-map` 🟢 (`access/admin-ui/webpack.config.js:34-35`).
- Dev-режим: `npm start` → порт 3000, `historyApiFallback`, прокси `/console-config.json` и `/api` на `ADMIN_CONSOLE_URL` (по умолчанию `http://localhost:8082`) 🟢 (`access/admin-ui/webpack.config.js:9, 36-46`; `access/admin-ui/package.json:8`).
- Jest: среда `jsdom`, тесты `src/**/*.test.ts(x)`, трансформация `ts-jest` с `tsconfig.json` 🟢 (`access/admin-ui/jest.config.js:1-6`).
- TypeScript: `strict`, `noUnusedLocals`, `noUnusedParameters`, `noFallthroughCasesInSwitch`, `jsx: react-jsx` 🟢 (`access/admin-ui/tsconfig.json:3-11`).
- Образ стенда `admin-console` собирается из jar (`access/admin-console/target/admin-console-*-exec.jar`), внутри которого бандл; отдельного образа у UI нет 🟢 (`platform/docker/docker-compose.yml:274-280`) — см. [`../../docker/stand-startup/requirements.md`](../../docker/stand-startup/requirements.md).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `./mvnw verify` модуля даёт jar с бандлом в `META-INF/resources` | Must | AC-01 |
| RF-02 | `npm test` выполняется в фазе `test` и пропускается `-DskipTests` | Must | AC-02 |
| RF-03 | Воспроизводимость: фиксированные Node/npm, `npm ci`, точные версии | Must | AC-03 |
| RF-04 | Бандл совместим с CSP консоли (CSS в файле, без inline) | Must | AC-04 |
| RF-05 | Локальная разработка с прокси на консоль | Could | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Воспроизводимость | Сборка не зависит от Node на машине | `access/admin-ui/pom.xml:19-21`; `docs/adr/0027-frontend-react-typescript-webpack.md:16` | 🟢 |
| Производительность CI | Node/npm и зависимости скачиваются заново на каждом прогоне (кэш настроен только для Maven) | `docs/adr/0027-frontend-react-typescript-webpack.md:34`; `.github/workflows/ci.yml:20-24` | 🟡 (так написано в ADR; сверено с `setup-java cache: maven`) |
| Безопасность цепочки поставок | Процесса проверки уязвимостей npm-зависимостей нет | `docs/adr/0027-frontend-react-typescript-webpack.md:36` | 🔴 Q-19 |

## Критерии приёмки

```gherkin
# AC-01
Дано чистая машина без Node, доступ к registry npm
Когда выполнен ./mvnw -B -pl access/admin-ui -am verify
Тогда в access/admin-ui/target/classes/META-INF/resources есть index.html и *.js с contenthash, а Node установлен в access/admin-ui/target

# AC-02
Дано модуль собирается с -DskipTests
Когда выполняется Maven
Тогда execution npm-test пропущен (skip=true), npm-build выполняется
Дано падает один Jest-тест
Когда выполняется ./mvnw verify без -DskipTests
Тогда сборка модуля падает в фазе test, бандл не собирается

# AC-03
Дано package-lock.json не совпадает с package.json
Когда выполняется npm ci
Тогда установка завершается ошибкой (npm ci не меняет lock-файл)                      # свойство npm ci; в коде репозитория отдельной проверки нет 🟡

# AC-04 (access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:183 «bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles»)
Дано бандл подключён к admin-console
Когда запрошен GET / (index.html)
Тогда ответ содержит CSP с default-src 'self', в index.html есть id="root", нет inline-скриптов и стилей

# AC-05
Дано запущена консоль на ADMIN_CONSOLE_URL
Когда выполнен npm start и открыт http://localhost:3000
Тогда /api и /console-config.json проксируются на консоль                              # вход через OIDC против стенда не завершится из-за redirect URI (Q-13) 🟡
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04 | Must | Один `verify` собирает всё (ADR 0027) |
| RF-05 | Could | Удобство разработки; вход на dev-сервере не настроен (Q-13) |
| Кэш Node/npm в CI, аудит npm-зависимостей | Won't | Перечислены в ADR как возможные улучшения |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-ui/pom.xml` | сборка Maven | 🟢 |
| `access/admin-ui/webpack.config.js` | Webpack | 🟢 |
| `access/admin-ui/package.json`, `access/admin-ui/package-lock.json` | зависимости | 🟢 |
| `access/admin-ui/tsconfig.json`, `access/admin-ui/jest.config.js` | TypeScript, Jest | 🟢 |
| `access/admin-ui/src/index.html`, `access/admin-ui/src/styles.css`, `access/admin-ui/src/declarations.d.ts` | шаблон, стили, декларация `*.css` | 🟢 |
| `access/pom.xml`, `pom.xml` | регистрация модуля, версия плагина | 🟢 |
