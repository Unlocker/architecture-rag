# Блок-схемы: platform/docker

> Archaeologist, платформа стенда. Пути относительно `platform/docker/` (`DC` = `docker-compose.yml`). Подробности и сомнения — `code-analysis.md` §W1, значения — `data-dictionary.md` §W.1. Метки 🟢/🟡/🔴 как в остальных блок-схемах.

## 1. Топология сервисов и порядок запуска (`DC:41-526`) 🟢

Стрелка «A → B» читается «B ждёт A»; `✓` — `service_healthy`, `⏹` — `service_completed_successfully`, `▶` — `service_started`. Профильные сервисы (`observability`) выделены пунктиром.

```mermaid
flowchart TD
  subgraph infra["инфраструктура"]
    certs["certs (одноразовый)\nalpine/openssl:3.5.4"]
    neo4j["neo4j 5-community 🔴 плавающий тег\nBolt TLS REQUIRED"]
    ninit["neo4j-init (одноразовый)\nпользователь archrag_reader"]
    pg["postgres:16"]
    s3["seaweedfs:4.48"]
    kc["keycloak:26.4 start-dev\nimport archrag-realm.json"]
  end
  subgraph apps["приложения"]
    ing["ingestion\nмиграции PG + схема Neo4j, :8081"]
    cpi["console-pg-init (одноразовый)\nпароль archrag_console_ro"]
    mcp["mcp-server (--scale N)"]
    con["admin-console\n127.0.0.1:8090"]
  end
  subgraph src["источники"]
    stubs["stub-eam/scm/cmdb/deploymap"]
    eamstub["eam-api-stub"]
    ad["adapter-eam/scm/cmdb/deploymap\n(replicas: 1)"]
  end
  proxy["reverse-proxy nginx:1.27-alpine\n127.0.0.1:8443 TLS"]

  certs -- "⏹" --> neo4j
  certs -- "⏹" --> proxy
  neo4j -- "✓" --> ninit
  neo4j -- "✓" --> ing
  pg -- "✓" --> ing
  s3 -- "✓" --> ing
  ing -- "✓" --> cpi
  ing -- "✓" --> mcp
  ing -- "✓" --> con
  ninit -- "⏹" --> mcp
  ninit -- "⏹" --> con
  neo4j -- "✓" --> mcp
  neo4j -- "✓" --> con
  kc -- "✓" --> mcp
  kc -- "✓" --> con
  kc -- "✓" --> proxy
  cpi -- "⏹" --> con
  pg -- "✓" --> ad
  s3 -- "✓" --> ad
  ing -- "✓" --> ad
  stubs -- "✓" --> ad
  eamstub -- "✓ (только adapter-eam)" --> ad
  mcp -- "✓" --> proxy
  ad -- "✓ все 4" --> proxy

  subgraph obs["профиль observability"]
    otel["otel-collector\n(без healthcheck)"]
    prom["prometheus v3.7.3"]
    graf["grafana 12.2.1\n127.0.0.1:3000"]
  end
  otel -. "▶" .-> prom
  prom -. "✓" .-> graf
  mcp -. "OTLP :4318 при ARCHRAG_OBSERVABILITY=true" .-> otel
  ing -. "OTLP :4318" .-> otel
```

Сети: `edge` (прокси, MCP, Keycloak, адаптеры, заглушки), `backend`/`graph`/`observability` — `internal`, `obs-edge`/`console-edge` — только для публикации портов (`DC:528-543`). Прокси стартует последним: `up --wait` возвращается, когда вся цепочка healthy. 🟡 Прокси ждёт всех четырёх адаптеров (`DC:460-463`), хотя проксирует к ним лишь вебхуки.

## 2. Поток аутентификации: Keycloak → прокси → приложения 🟢

Для агента (`archrag-demo`, `client_credentials`) и оператора консоли (`archrag-admin-console`, Authorization Code + PKCE). Конфигурация: `keycloak/archrag-realm.json:5-109`, `proxy/nginx.conf:18-40`, `DC:137-163`.

```mermaid
flowchart TD
  subgraph agent["MCP-клиент (агент)"]
    A1["POST https://localhost:8443/realms/archrag/protocol/openid-connect/token\ngrant_type=client_credentials, client archrag-demo"]
  end
  A1 --> PX1["reverse-proxy: location /realms/archrag/protocol/openid-connect/\n(админка Keycloak не проксируется)"]
  PX1 --> KC["keycloak: iss = http://keycloak:8080/realms/archrag\n(фиксирован KC_HOSTNAME)"]
  KC --> TK["access token:\naud = ARCHRAG_MCP_RESOURCE_URI (mapper mcp-audience)\nscope = architecture.read (default)\n+ aud urn:archrag:admin при scope=architecture.admin"]
  TK --> A2["POST https://localhost:8443/mcp + Bearer"]
  A2 --> PX2["reverse-proxy: TLS 1.2/1.3, proxy_pass http://mcp-server:8080\n(proxy_buffering off)"]
  PX2 --> M1{"mcp-server: JWT\nissuer + audience = MCP URI?"}
  M1 -- нет --> R401["401"]
  M1 -- да --> M2{"ToolScopeFilter:\nscope tool из tool-scopes?"}
  M2 -- нет --> R403["403 insufficient_scope"]
  M2 -- да --> M3["tool → graph-query-core\nNeo4j под archrag_reader"]

  subgraph op["оператор (браузер)"]
    B1["http://127.0.0.1:8090 → admin-ui\nredirect на keycloak:8080\n(/etc/hosts: 127.0.0.1 keycloak)"]
  end
  B1 --> KC2["keycloak: auth endpoint\nPKCE S256, redirect только 127.0.0.1:8090"]
  KC2 --> TK2["access token:\naud urn:archrag:console (client mapper)\n+ urn:archrag:admin при scope architecture.admin\nazp = archrag-admin-console"]
  TK2 --> C1{"admin-console /api/**:\nJWT + aud консоли?"}
  C1 -- нет --> C401["401"]
  C1 -- да --> C2{"authority SCOPE_architecture.admin?"}
  C2 -- нет --> C403["403"]
  C2 -- да --> C3["чтение графа (reader) и PG (archrag_console_ro)"]
  TK2 -. "токен консоли на ingestion /admin/replay" .-> I1{"ingestion: aud urn:archrag:admin\nи azp ∈ allowed-clients?"}
  I1 -- "azp = archrag-admin-console\n(не в списке)" --> I401["401"]
  TK -. "scope=architecture.admin,\nazp = archrag-demo" .-> I2["ingestion /admin/** → 200\n(restore.sh: replay)"]
```

🔴 Роль `archrag-console-admin` присвоена пользователю (`realm.json:37-46`), но нигде не проверяется: токен консоли получает любой пользователь realm (ADR 0026, `docs/05-production-gap-list.md:33`).

## 3. `scripts/backup.sh`, `restore.sh`, `backup-restore-check.sh` 🟢

Коды: `die` → 1. 🟡 `trap ERR` не срабатывает на сбой внутри функций `lib.sh` (нет `set -E`, проверено локальным экспериментом): после ошибки dump/load сервисы могут остаться остановленными.

```mermaid
flowchart TD
  subgraph chk["backup-restore-check.sh (приёмка E5.4)"]
    C0["ждать пустой очереди (300 с)\nF0 = graph-fingerprint"]
    C0 --> CB["backup.sh <метка check-UTC>"]
    CB --> CU["upsert itsystems 9001 (новый) и 5101 (изменённый)\nв eam-api-stub (:8091/control/upsert)"]
    CU --> CW["ждать событий в inbox_event\n(source_id + source_version, до 300 с) + пустую очередь"]
    CW --> C1["F1 = fingerprint"]
    C1 --> CD{"F1 ≠ F0?"}
    CD -- нет --> CF1["FAILED: сценарий ничего не проверяет"]
    CD -- да --> CR["restore.sh <метка>"]
    CR --> C2["F2 = fingerprint"]
    C2 --> CE{"F2 = F1?"}
    CE -- нет --> CF2["diff F1/F2, FAILED"]
    CE -- да --> COK["OK, код 0"]
  end

  subgraph bk["backup.sh"]
    B0["check_label; started_at = UTC now"] --> B1["vol_sh: пустой /backups/<метка> в томе backups"]
    B1 --> B2["trap ERR → start_graph_services"]
    B2 --> B3["stop: ingestion, mcp-server, neo4j"]
    B3 --> B4["neo4j-admin database dump neo4j"]
    B4 --> B5["start: neo4j, ingestion, mcp-server (--wait, до 900 с)"]
    B5 --> B6["pg_dump -Fc → postgres.dump"]
    B6 --> B7["manifest.json: метка, backup_started_at,\nверсия Neo4j, sha256 двух дампов"]
    B7 --> B8["aws-cli (ключи через stdin): s3 cp --recursive\n→ s3://BACKUP_BUCKET/<метка>/ (бакет создаётся при отсутствии)"]
  end

  subgraph rs["restore.sh"]
    R0["s3 cp ← S3 в том backups"] --> R1{"manifest + neo4j.dump есть,\nsha256 совпадают,\nверсия Neo4j = версии образа?"}
    R1 -- нет --> RF["die ДО остановки сервисов"]
    R1 -- да --> R2["stop ingestion, mcp-server, neo4j"]
    R2 --> R3["neo4j-admin database load neo4j --overwrite-destination=true"]
    R3 --> R4["start neo4j, ingestion, mcp-server"]
    R4 --> R5["POST /admin/replay на каждый из urn:corp:{eam,scm,cmdb,deploymap}\nокно [backup_started_at − 5 мин, now + 1 мин)\nтокен archrag-demo, scope architecture.admin, из контейнера ingestion"]
    R5 --> R6["wait_queue_empty 600 с\n(QUARANTINED → die)"]
  end
  NOTE["PostgreSQL при restore НЕ восстанавливается (журнал — источник replay).\nУдаления reconcile после backup replay не повторяет → POST /admin/reconcile/{source}"]
  R6 --- NOTE
```

## 4. Smoke-проверки: `smoke.sh` и `console-smoke.sh` 🟢

В CI запускается только `smoke.sh` (`.github/workflows/ci.yml:60-101`) 🔴; `console-smoke.sh` — вручную.

```mermaid
flowchart TD
  subgraph s1["smoke.sh (внешняя граница, критерий 6)"]
    S0["ca.crt из тома certs (без -k)"] --> S1{"POST /mcp без токена = 401?"}
    S1 -- нет --> SF["smoke FAILED (код 1)"]
    S1 -- да --> S2{"токен client_credentials → /mcp = 200\nи tools/call ping возвращает result?"}
    S2 -- нет --> SF
    S2 -- да --> S3{"plain HTTP на TLS-порт ≠ 200?\n7474, 7687, 5432 закрыты на хосте?"}
    S3 -- нет --> SF
    S3 -- да --> S4{"mcp-server: нет ARCHRAG_PG_*, NEO4J_PASSWORD,\nARCHRAG_NEO4J_USER, ARCHRAG_S3_*, WEBHOOK_SECRET;\n/run/secrets = только reader;\nконтроль: neo4j резолвится и достижим; postgres нет"}
    S4 -- нарушено --> SF
    S4 -- ок --> S5{"proxy не резолвит ingestion\n(контроль: резолвит mcp-server)"}
    S5 -- нет --> SF
    S5 -- да --> S6{"admin REST изнутри ingestion:\nscope admin = 200, scope read = 401"}
    S6 -- нет --> SF
    S6 -- да --> S7{"plain Bolt к neo4j:7687 закрывается без ответа (CLOSED)?"}
    S7 -- нет --> SF
    S7 -- да --> SOK["smoke ok, код 0"]
  end

  subgraph s2["console-smoke.sh (консоль, U.5)"]
    K1["console-config.json называет клиент консоли;\nлогин Authorization Code + PKCE S256 на curl\n(curl --connect-to вместо /etc/hosts)"] --> K2{"direct grant для публичного клиента: 400/401?"}
    K2 -- нет --> KF["console-smoke FAILED (код 1)"]
    K2 -- да --> K3{"/api/* без токена = 401;\nтокен только architecture.read = 403;\nтокен archrag-demo (aud MCP) = 401"}
    K3 -- нет --> KF
    K3 -- да --> K4{"admin-токен: /api/graph/stats = 200,\n/api/sync/sources = 200 и ровно 4 источника"}
    K4 -- нет --> KF
    K4 -- да --> K5{"токен консоли (aud admin, azp консоли)\nна ingestion /admin/replay = 401"}
    K5 -- нет --> KF
    K5 -- да --> K6{"публикация на 127.0.0.1; на внешнем IP хоста\nпорты консоли и Keycloak закрыты (skip без IP);\nproxy не резолвит admin-console"}
    K6 -- нет --> KF
    K6 -- да --> K7{"/run/secrets консоли = reader + console_ro;\nнет writer/S3 окружения; контроль: neo4j и postgres достижимы"}
    K7 -- нет --> KF
    K7 -- да --> KOK["console-smoke ok, код 0"]
  end
```

## 5. Сценарий смены commit развёртывания: `scripts/deployment-commit-check.sh` (UNLOCKER-248) 🟢

```mermaid
flowchart TD
  D0["токен archrag-demo (scope read) через прокси"] --> D1["ждать PROJECTED для dep-payments-api-prod и пустую очередь (300 с)"]
  D1 --> D2["search_assets 'Payments Core' (ITSystem) → systemGid"]
  D2 --> D3["find_runtime_footprint(systemGid, prod) → sourceCommit, deployedFrom.commit, validFrom"]
  D3 --> D4{"sourceCommit = deployedFrom.commit\nи ∈ {COMMIT_A, COMMIT_B}?"}
  D4 -- нет --> DF["die (код 1)"]
  D4 -- да --> D5["выбрать противоположный commit;\nPOST stub-deploymap /control/upsert (deploymap-poc-1)"]
  D5 --> D6["ждать события в inbox_event (до 300 с) и пустую очередь"]
  D6 --> D7["find_runtime_footprint ещё раз:\ncommit = новый, validFrom изменился,\nrepository.url = repositoryUrl"]
  D7 --> D8{"Neo4j (writer, cypher-shell):\nоткрытых DEPLOYED_FROM = 1, с новым commit = 1,\nзакрытых со старым = 1 → '1,1,1'?"}
  D8 -- нет --> DF
  D8 -- да --> DOK["OK, код 0"]
```

Замечание: схемы 3–5 описывают скрипты; `measure.sh` (замеры критерия 9, код 1 при превышении порогов/росте DLQ/ошибке tool) описан в `code-analysis.md` §W1.7 таблицей и отдельной блок-схемой не оформлен — это линейный сбор метрик без ветвления, кроме проверок порогов.
