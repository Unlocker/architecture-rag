# run-source-stub: задачи

## Pré-requisitos
- [ ] Модуль `source-stubs` (`StubSource`, `StubSources`, `StubSourceServer`, `StubWebhookSender`) собирается
- [ ] Для webhook: адаптер того же источника слушает `POST /webhook`; `ARCHRAG_WEBHOOK_TARGET` и `ARCHRAG_WEBHOOK_SECRET` совпадают с настройкой адаптера (platform/docker/docker-compose.yml:381-389)

## Tarefas

- [ ] T-01: `LauncherConfig.Stub` и `stub(...)`: порт, цель и секрет webhook, правило «секрет обязателен только при цели»
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:80-86, 142-153
  - Критерий готовности: `LauncherConfigTest` — `stubNeedsNoSecretsAndHasDefaultPort`, `stubWebhookTargetRequiresSecret`, `stubWebhookTargetWithSecretIsParsedAndSecretIsHidden`, `stubWithoutTargetHasNoWebhook`
  - Уверенность: 🟢
- [ ] T-02: `DemoLauncher.runStub`: seed, отправитель webhook, сервер источника, управляющий вход, ожидание
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:85-95
  - Критерий готовности: сервис отвечает на `GET /changes?limit=1`; control отвечает на 8091
  - Уверенность: 🟢
- [ ] T-03: `StubControl`: `POST /control/upsert`, лимит 64 КиБ, валидация, `notify`, `webhookStatus`
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:29-125
  - Критерий готовности: AC-02..AC-07; `StubControlTest` (10 тестов)
  - Уверенность: 🟢
- [ ] T-04: Compose-сервисы `stub-eam/scm/cmdb/deploymap` (`networks: [edge]`, `ARCHRAG_WEBHOOK_TARGET`, secret `webhook_secret`, healthcheck)
  - Origin: platform/docker/docker-compose.yml:381-428
  - Критерий готовности: `docker compose up -d --wait`; скрипты стенда вызывают control изнутри контейнера (`platform/docker/scripts/deployment-commit-check.sh:107`)
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `StubControlTest` (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:55-183)
- [ ] TT-02: `LauncherConfigTest` — ветка stub (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:89-126)
- [ ] TT-03: 🔴 нет теста `runStub` (запуск обоих серверов, порты); 🔴 нет теста исключения вне `IllegalArgumentException` в `handleUpsert` (Q-05)

## Ordem Sugerida
1. T-01 → 2. T-03 → 3. T-02 → 4. T-04.

## Lacunas Pendentes (🔴 и 🟡)
- Q-02, Q-05, Q-07, Q-12, Q-15, Q-16 — [`../questions.md`](../questions.md).
