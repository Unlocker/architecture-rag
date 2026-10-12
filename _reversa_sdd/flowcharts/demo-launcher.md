# Блок-схемы: platform/demo-launcher

> Archaeologist, R.2e. Пути относительно `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/`. Разбор — `code-analysis.md` §Y2, env и эндпоинты — `data-dictionary.md` §Y.2. Топология compose — `platform/docker/docker-compose.yml`.

## Диспетчер `DemoLauncher.main` (`DemoLauncher.java:58-83`) 🟢

```mermaid
flowchart TD
  M["main(args)"] --> A1{"args == [eam-api-stub]?"}
  A1 -- да --> C1["LauncherConfig.eamApiStub(env)"]
  C1 --> RE["runEamApiStub"]
  A1 -- нет --> A2{"2 аргумента и args[0] ∈ adapter, stub?"}
  A2 -- нет --> U["usage в stderr; exit 2"]
  A2 -- да --> PS["LauncherConfig.parseSystem(args[1])\nEAM | SCM | CMDB | DEPLOY_MAP"]
  PS --> K{"args[0]"}
  K -- adapter --> CA["LauncherConfig.adapter(system, env)"]
  CA --> RA["runAdapter"]
  K -- stub --> CS["LauncherConfig.stub(system, env)"]
  CS --> RS["runStub"]
  C1 -. IllegalArgumentException .-> CE
  PS -. IllegalArgumentException .-> CE
  CA -. IllegalArgumentException .-> CE
  CS -. IllegalArgumentException .-> CE["stderr: configuration error: имена переменных;\nexit 2 (🟡 так же для IAE времени выполнения)"]
```

## Старт адаптера `runAdapter` (`DemoLauncher.java:108-160`) 🟢

```mermaid
flowchart TD
  R["runAdapter(config)"] --> DS["PGSimpleDataSource(pgUrl, user, password);\nPostgresEventJournal(ds)\nмиграции НЕ накатываются: их делает ingestion-service"]
  DS --> AC["AdapterConfig(system, sourceUrl, webhookSecret,\nreplayWindow 5 мин, pageSize 100,\npollInterval, reconcileInterval, RetryPolicy.defaults())"]
  AC --> S3["S3RawPayloadStore.create(endpoint, keys, bucket);\nstore.ensureBucket()"]
  S3 --> CR{"create(system)"}
  CR -- "EAM, eamApi == null" --> E1["EamAdapter.create(config, journal, store) — legacy"]
  CR -- "EAM, eamApi != null" --> E2["EamAdapter.create(config, eamApi, journal, store)"]
  CR -- SCM --> E3["ScmAdapter.create"]
  CR -- CMDB --> E4["AssetAdapter.create"]
  CR -- DEPLOY_MAP --> E5["DeploymapAdapter.create"]
  E1 & E2 & E3 & E4 & E5 --> HS["healthServer(healthPort, started=false):\nGET /health → 503 DOWN"]
  HS --> SW["adapter.startWebhook(0.0.0.0:webhookPort)"]
  SW --> SP["adapter.startPolling()"]
  SP --> SR["adapter.startReconciliation()"]
  SR --> ST["started = true → /health 200 UP"]
  ST --> AW["awaitShutdown: CountDownLatch + shutdown hook (SIGTERM/SIGINT)"]
  AW --> CL["finally: health.stop(0);\nclose adapter и S3 (🟡 JVM не ждёт главный поток после хука)"]
```

## Разбор env: `LauncherConfig` (`LauncherConfig.java:107-203`) 🟢

```mermaid
flowchart TD
  AD["adapter(system, env)"] --> RQ["required: PG_URL, PG_USER, PG_PASSWORD, S3_ENDPOINT,\nS3_ACCESS_KEY, S3_SECRET_KEY, S3_BUCKET, SOURCE_URL, WEBHOOK_SECRET\nпусто или пробелы → имя в missing"]
  RQ --> SE{"system == EAM?"}
  SE -- нет --> NE["eamApi = null"]
  SE -- да --> MO{"ARCHRAG_EAM_MODE"}
  MO -- "пусто / legacy" --> NE
  MO -- "eam-api" --> RU["required EAM_API_URL, EAM_API_TOKEN\n(имена в missing); EamApi(URI, token)"]
  MO -- "иное" --> BAD["IllegalArgumentException сразу"]
  NE --> MS
  RU --> MS{"missing не пуст?"}
  MS -- да --> THR["IllegalArgumentException:\nrequired environment variables are not set: [имена]"]
  MS -- нет --> OPT["URI.create(S3_ENDPOINT, SOURCE_URL);\nport WEBHOOK_PORT=8080, HEALTH_PORT=8090 (1..65535);\nseconds POLL=30, RECONCILE=21600 (≥ 1)"]
  OPT --> REC["Adapter(...) с toString без секретов"]
  ST["stub(system, env)"] --> TG{"WEBHOOK_TARGET задан?"}
  TG -- нет --> S0["Stub(system, port STUB_PORT=8080, null, null)\nWEBHOOK_SECRET игнорируется"]
  TG -- да --> SC{"WEBHOOK_SECRET задан?"}
  SC -- нет --> THR2["IllegalArgumentException: WEBHOOK_TARGET requires ..."]
  SC -- да --> S1["Stub(system, port, URI(target.trim()), secret)"]
  EA["eamApiStub(env)"] --> TK{"EAM_API_TOKEN задан?"}
  TK -- нет --> THR3["IllegalArgumentException"]
  TK -- да --> E0["EamApiStub(port STUB_PORT=8080, token)"]
```

## `POST /control/upsert` заглушки источника (`StubControl.java:52-104`) 🟢

```mermaid
flowchart TD
  R["POST /control/upsert (порт 8091)"] --> M{"метод POST?"}
  M -- нет --> R405["405 {error: POST expected}"]
  M -- да --> RB["readNBytes(64 KiB + 1)"]
  RB --> SZ{"тело > 64 KiB?"}
  SZ -- да --> R400["400 {error: invalid request} — без эха ввода"]
  SZ -- нет --> JP["adapter-core Json.parseObject (глубина ≤ 64)"]
  JP --> VA{"type и id — непустые строки?\npayload — объект?"}
  VA -- нет --> R400
  VA -- да --> UP["source.upsert(type, id, payload) → sourceVersion"]
  UP --> NF{"webhooks != null и notify != false?"}
  NF -- нет --> OK1["200 {sourceVersion}"]
  NF -- да --> EV["последнее событие (type, id) из source.webhookEvents()"]
  EV --> EN{"событие найдено?"}
  EN -- нет --> W0["webhookStatus = 0"]
  EN -- да --> SD["webhooks.send(event):\nподписанный POST на ARCHRAG_WEBHOOK_TARGET"]
  SD -- "HTTP-статус" --> WS["webhookStatus = статус адаптера"]
  SD -- "IllegalStateException" --> WL["WARNING в лог; webhookStatus = 0\n(upsert не откатывается: догонит polling)"]
  W0 --> OK2["200 {sourceVersion, webhookStatus}"]
  WS --> OK2
  WL --> OK2
```

## Управление заглушкой EAM API `EamApiControl` (`EamApiControl.java:33-98`) 🟢

```mermaid
flowchart TD
  R["POST /control/upsert | /control/archive (порт 8091)"] --> M{"метод POST?"}
  M -- нет --> R405["405 {error: POST expected}"]
  M -- да --> RB["тело ≤ 64 KiB → Json.parseObject"]
  RB -. "не JSON / слишком большое / IAE" .-> R400["400 {error: invalid request}"]
  RB --> T{"type ∈ platforms, itproducts, itsystems?\n(литерал Set.of, 🔴 не из EamApiSeed)"}
  T -- нет --> R400
  T -- да --> I{"id — JSON-целое (Long)?"}
  I -- нет --> R400
  I -- да --> WH{"путь"}
  WH -- "/control/upsert" --> AT{"attrs — объект?"}
  AT -- нет --> R400
  AT -- да --> SU["store.upsert(type, id, attrs): attrs заменяются целиком;\nver растёт только при изменении"]
  SU --> OKU["200 {ver: N}"]
  WH -- "/control/archive" --> SA["store.archive(type, id) — идемпотентно"]
  SA --> OKA["200 {}"]
```
