# build-bundle: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §5. Потребитель артефакта — `access/admin-console` ([`../../flowcharts/admin-console.md`](../../flowcharts/admin-console.md)); образ — [`../../docker/stand-startup/design.md`](../../docker/stand-startup/design.md).

## Interface

| Артефакт / команда | Содержимое | Трассировка |
|--------------------|------------|-------------|
| Maven-модуль `admin-ui` (родитель `access`) | `jar` без Java-кода; ресурсы — результат Webpack | `access/admin-ui/pom.xml:7-16`; `access/pom.xml:19-22` 🟢 |
| `npm run build` | `webpack --mode production` | `access/admin-ui/package.json:7` 🟢 |
| `npm start` | `webpack serve --mode development` (порт 3000) | `access/admin-ui/package.json:8`; `access/admin-ui/webpack.config.js:36-46` 🟢 |
| `npm test` | `jest` | `access/admin-ui/package.json:9`; `access/admin-ui/jest.config.js:1-6` 🟢 |
| Зависимость `admin-console` → `admin-ui` | `${project.version}`, комментарий «бандл SPA отдаёт Spring Boot как статику» | `access/admin-console/pom.xml:19-24` 🟢 |

## Fluxo Principal

1. `generate-resources`: `install-node-and-npm` (Node `v22.12.0`, npm `10.9.0`, каталог `target/`), затем `npm ci` (`access/admin-ui/pom.xml:35-55`).
2. `test`: `npm test` (если не `-DskipTests`) (`access/admin-ui/pom.xml:56-66`).
3. `prepare-package`: `npm run build` (`access/admin-ui/pom.xml:67-76`). Webpack: вход `./src/index.tsx`; `ts-loader` (исключая `node_modules`), CSS через `MiniCssExtractPlugin.loader` + `css-loader`; `HtmlWebpackPlugin` по шаблону `./src/index.html`; выход `target/classes/META-INF/resources` (`access/admin-ui/webpack.config.js:12-30`).
4. `package`: Maven упаковывает `target/classes` в jar; `admin-console` подключает его зависимостью, Spring Boot отдаёт `META-INF/resources` как статику (`access/admin-console/pom.xml:19-24`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:33-40`).
5. На стенде: образ `archrag/admin-console:dev` собирается из `admin-console-*-exec.jar`; порт публикуется на `127.0.0.1` (`platform/docker/docker-compose.yml:274-293`).

## Fluxos Alternativos

- `-DskipTests` → `npm-test` пропущен, остальное выполняется (`access/admin-ui/pom.xml:22-23, 64`).
- Нет доступа к registry npm или `package-lock.json` не совпадает с `package.json` → `npm ci` падает, сборка модуля падает (поведение `npm ci`; в коде не проверяется) 🟡.
- `webpack serve`: `historyApiFallback` возвращает `index.html` для путей без файла; `/console-config.json` и `/api` проксируются с `changeOrigin` на `ADMIN_CONSOLE_URL` (`access/admin-ui/webpack.config.js:36-46`).
- Режим `development` включает `source-map` (`access/admin-ui/webpack.config.js:35`).

## Dependências

`frontend-maven-plugin` 1.15.1 (`pom.xml:37, 202-206`); npm: `react`/`react-dom` 18.3.1, `cytoscape` 3.30.4, `oidc-client-ts` 3.1.0; dev: `typescript` 5.7.2, `webpack` 5.111.1, `webpack-cli` 5.1.4, `webpack-dev-server` 6.0.0, `ts-loader`, `css-loader`, `mini-css-extract-plugin`, `html-webpack-plugin`, `jest` 29.7.0, `ts-jest`, `jest-environment-jsdom`, `@testing-library/react` 16.1.0 (`access/admin-ui/package.json:11-34`). Новые зависимости — только по решению архитектора (CLAUDE.md репозитория; ADR 0027).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Фронтенд собирается внутри Maven, а не отдельным npm-шагом | `access/admin-ui/pom.xml:26-78`; `docs/adr/0027-frontend-react-typescript-webpack.md:22-28` | 🟢 |
| CSS в файл (CSP запрещает inline), HTML не минифицируется Webpack-ом | `access/admin-ui/webpack.config.js:26-33` | 🟢 |
| Имена с `contenthash`, `clean: true` на выходе | `access/admin-ui/webpack.config.js:13-18` | 🟢 |
| Исходные карты не публикуются в production | `access/admin-ui/webpack.config.js:34-35` | 🟢 |
| Статику отдаёт `admin-console` без кэша (`CacheControl.noCache()`) | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:33-40` | 🟢 |

## Riscos e Lacunas

- 🔴 Нет проверки уязвимостей npm и кэша Node/npm в CI — Q-19.
- 🟡 Dev-сервер не совпадает с redirect URI Keycloak — Q-13.
- 🟡 Смена версии Webpack/Node меняет поведение минификации HTML (комментарий в конфигурации фиксирует, что webpack 5.111+ сам минифицирует HTML), защита — Java-тест `bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles` (`access/admin-ui/webpack.config.js:31-33`; `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:183`).
