# run-eam-api-stub: задачи

## Pré-requisitos
- [ ] Модуль `source-stubs` (`EamApiStub`, `EamApiStore`, `EamApiSeed`) собирается
- [ ] Токен `ARCHRAG_EAM_API_TOKEN` доставляется Docker secret `eam_api_token` (platform/docker/docker-compose.yml:438-439)

## Tarefas

- [ ] T-01: `LauncherConfig.EamApiStub` и `eamApiStub(...)`: токен обязателен, порт по умолчанию 8080
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:89-95, 156-163
  - Критерий готовности: AC-01; `LauncherConfigTest.eamApiStubRequiresTokenAndHidesItInToString`
  - Уверенность: 🟢
- [ ] T-02: `DemoLauncher.runEamApiStub`: seed, `EamApiStub`, `EamApiControl`, ожидание, превращение `IOException` в `IllegalStateException`
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:97-106
  - Критерий готовности: заглушка отвечает на `/api/platforms/?page_size=1` с токеном; control — на 8091
  - Уверенность: 🟢
- [ ] T-03: `EamApiControl`: `POST /control/upsert|archive`, allowlist `type`, числовой `id`, лимит 64 КиБ, 400 без эха
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:23-112
  - Критерий готовности: AC-02..AC-05; `EamApiControlTest` (4 теста)
  - Уверенность: 🟢
- [ ] T-04: Compose-сервис `eam-api-stub` (`command: ["eam-api-stub"]`, `networks: [edge]`, secret, healthcheck с токеном)
  - Origin: platform/docker/docker-compose.yml:430-445
  - Критерий готовности: `docker compose up -d --wait`; `adapter-eam` стартует после healthy
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `EamApiControlTest` (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:47-80)
- [ ] TT-02: `LauncherConfigTest` — ветка EAM API stub (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:95-104)
- [ ] TT-03: 🔴 нет теста согласованности `EamApiControl.TYPES` и `EamApiSeed` (Q-03)
- [ ] TT-04: 🔴 нет теста `runEamApiStub` и `archive` неизвестного объекта/неверного `type` у `/control/archive` (Q-05)

## Ordem Sugerida
1. T-01 → 2. T-03 → 3. T-02 → 4. T-04.

## Lacunas Pendentes (🔴 и 🟡)
- Q-02, Q-03, Q-05, Q-12 — [`../questions.md`](../questions.md).
