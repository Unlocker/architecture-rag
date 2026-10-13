# build-bundle: задачи

## Pré-requisitos
- [ ] Родитель `access` и корневой `pom.xml` с `frontend-maven-plugin.version` (`pom.xml:37, 202-206`)
- [ ] Доступ к registry npm и к загрузке Node (при первой сборке)

## Tarefas

- [ ] T-01: Модуль `admin-ui` в родителе `access` и зависимость `admin-console` → `admin-ui`
  - Origin: `access/pom.xml:19-22`; `access/admin-ui/pom.xml:7-16`; `access/admin-console/pom.xml:19-24`
  - Критерий готовности: `./mvnw -pl access/admin-console -am verify` собирает UI раньше консоли
  - Уверенность: 🟢
- [ ] T-02: Execution'ы `frontend-maven-plugin` (install, `npm ci`, `npm test` со `skipTests`, `npm run build`)
  - Origin: `access/admin-ui/pom.xml:18-78`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-03: Webpack: вход, `ts-loader`, CSS-файл, `HtmlWebpackPlugin`, выход в `META-INF/resources`, `contenthash`, `devtool`
  - Origin: `access/admin-ui/webpack.config.js:11-35`
  - Критерий готовности: AC-01, AC-04
  - Уверенность: 🟢
- [ ] T-04: Dev-сервер (порт 3000, `historyApiFallback`, прокси `/console-config.json` и `/api`)
  - Origin: `access/admin-ui/webpack.config.js:9, 36-46`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-05: `package.json` (скрипты, точные версии), `package-lock.json`, `tsconfig.json`, `jest.config.js`, `declarations.d.ts`, `index.html`
  - Origin: `access/admin-ui/package.json:1-35`; `access/admin-ui/tsconfig.json:1-18`; `access/admin-ui/jest.config.js:1-6`; `access/admin-ui/src/declarations.d.ts:1`; `access/admin-ui/src/index.html:1-11`
  - Критерий готовности: AC-03
  - Уверенность: 🟢
- [ ] T-06: `.gitignore` модуля (`node_modules/`)
  - Origin: `access/admin-ui/.gitignore:1`
  - Критерий готовности: `node_modules` не попадает в Git
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: AC-04 проверяет Java-тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:183`
- [ ] TT-02: 🔴 нет автоматической проверки уязвимостей npm-зависимостей (Q-19)
- [ ] TT-03: 🟡 CI запускает только `./mvnw verify` (`.github/workflows/ci.yml:25`) — он включает `npm test` и сборку бандла; отдельного шага или артефакта для бандла нет

## Ordem Sugerida
T-05 → T-03 → T-02 → T-01 → T-04 → T-06.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-19 (🔴); Q-13 (🟡).
