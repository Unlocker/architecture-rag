# network-secrets: задачи

## Pré-requisitos
- [ ] Поднятый стенд ([`../stand-startup/tasks.md`](../stand-startup/tasks.md)); `.env` рядом со скриптами; curl, openssl, python3

## Tarefas

- [ ] T-01: Сети и публикация портов (`edge`, `backend`/`graph`/`observability` как `internal`, `obs-edge`, `console-edge`, `127.0.0.1` в `ports`)
  - Origin: `platform/docker/docker-compose.yml:528-543, 160-161, 292-293, 450-451, 515-516`
  - Критерий готовности: AC-01, AC-04
  - Уверенность: 🟢
- [ ] T-02: Распределение секретов по сервисам (минимум прав): `mcp-server` — только reader; `admin-console` — reader и `console_pg_password`; `ingestion` — writer, PG, S3
  - Origin: `platform/docker/docker-compose.yml:202-206, 238-239, 287-289, 545-564`
  - Критерий готовности: AC-03, AC-04
  - Уверенность: 🟢
- [ ] T-03: TLS: демо-CA и сертификаты (`certs`), `nginx` `listen 8443 ssl`, Neo4j `tls_level=REQUIRED`
  - Origin: `platform/docker/certs/gen-certs.sh:12-40`; `platform/docker/proxy/nginx.conf:6-10`; `platform/docker/docker-compose.yml:62-67`
  - Критерий готовности: AC-02, AC-06
  - Уверенность: 🟢
- [ ] T-04: `smoke.sh` (девять проверок)
  - Origin: `platform/docker/smoke.sh:1-89`
  - Критерий готовности: AC-01..AC-03, AC-05, AC-06; код 0 на здоровом стенде, код 1 с причиной иначе
  - Уверенность: 🟢
- [ ] T-05: `console-smoke.sh` (семь проверок)
  - Origin: `platform/docker/console-smoke.sh:1-126`
  - Критерий готовности: AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-06: Ограничение маршрутов proxy (только перечисленные `location`; админка Keycloak и `/control/snapshot` не проксируются)
  - Origin: `platform/docker/proxy/nginx.conf:13-62`
  - Критерий готовности: AC-01
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `smoke.sh` — в CI трижды (`.github/workflows/ci.yml:78, 81, 85, 92`)
- [ ] TT-02: 🟢 [R.8: ответ владельца, вопрос 14] `console-smoke.sh` — не в CI (Q-07)
- [ ] TT-03: 🟡 нет проверки, что секреты не попадают в argv процессов (Q-08), и проверки заголовков безопасности proxy (Q-11)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-06 → T-04 → T-05.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-07 (🟢 [R.8: ответ владельца, вопрос 14]); Q-04, Q-08, Q-09, Q-11, Q-15, Q-22 (🟡).
