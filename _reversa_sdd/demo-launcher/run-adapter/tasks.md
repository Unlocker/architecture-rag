# run-adapter: задачи

## Pré-requisitos
- [ ] `ingestion-service` применил миграции журнала (inbox, checkpoints); PostgreSQL и S3 доступны
- [ ] Источник (`stub-<src>` либо `eam-api-stub`) отвечает; секреты доставлены как Docker secrets (platform/docker/docker-compose.yml:22-26)

## Tarefas

- [ ] T-01: `LauncherConfig.Adapter` и `adapter(...)`: девять обязательных переменных, необязательные порты и интервалы, `eamApi` для EAM
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:49-71, 107-125, 128-139
  - Критерий готовности: AC-01, AC-03, AC-04; `LauncherConfigTest`
  - Уверенность: 🟢
- [ ] T-02: `DemoLauncher.runAdapter`: журнал PG → `AdapterConfig` → S3 `ensureBucket` → адаптер → health → webhook → polling → reconciliation → `started`
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:108-135
  - Критерий готовности: порядок старта строго соблюдён; `try-with-resources` закрывает адаптер и S3
  - Уверенность: 🟢
- [ ] T-03: `DemoLauncher.create`: выбор адаптера по `SourceSystem` (EAM с `eamApi` или без)
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:137-145
  - Критерий готовности: все четыре источника покрыты `switch` (компилятор проверяет полноту)
  - Уверенность: 🟢
- [ ] T-04: `DemoLauncher.healthServer`: `GET /health` 200/503
  - Origin: platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160
  - Критерий готовности: AC-02; healthcheck compose проходит после старта
  - Уверенность: 🟢
- [ ] T-05: Compose-сервисы `adapter-eam/scm/cmdb/deploymap` (secrets, `edge`+`backend`, `depends_on`, `replicas: 1`, healthcheck)
  - Origin: platform/docker/docker-compose.yml:311-378
  - Критерий готовности: `docker compose up -d --wait` зелёный; адаптеры стартуют после `ingestion`
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `LauncherConfigTest` — адаптер: умолчания, переопределения, ошибки, секреты, режимы EAM (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:28-80, 128-158)
- [ ] TT-02: 🔴 нет теста `runAdapter`/`/health` (Q-05)
- [ ] TT-03: Смоук стенда `platform/docker/smoke.sh` (вне модуля) подтверждает запуск ролей

## Ordem Sugerida
1. T-01 → 2. T-04 → 3. T-03 → 4. T-02 → 5. T-05.

## Lacunas Pendentes (🔴 и 🟡)
- Q-04, Q-05, Q-06, Q-11, Q-13 — [`../questions.md`](../questions.md).
