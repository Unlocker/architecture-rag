# observability-measure: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §3, §9. Метрики ingestion: [`../../ingestion-service/contracts.md`](../../ingestion-service/contracts.md) §3. Блок-схема сценария commit: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §5. Результаты замеров: `docs/04-poc-measurements.md`.

## Visão Geral

Три связанных сценария стенда: (1) профиль `observability` — OTLP-метрики `mcp-server` и `ingestion` → OTel Collector → Prometheus → Grafana (дашборд «Arch RAG PoC»); (2) `measure.sh` — замеры критерия 9 PoC на поднятом стенде с профилем (p95 tools, задержка «источник → граф» по webhook и polling, throughput, sync lag, DLQ/ошибки, поведение `trace_dependencies` по глубине); (3) `deployment-commit-check.sh` — приёмка фичи «смена commit развёртывания» (UNLOCKER-248, ADR 0029) 🟢 (`platform/docker/README.md:129-152, 191-206, 238-244`).

## Regras de Negócio

- Метрики по умолчанию выключены (`ARCHRAG_OBSERVABILITY=false` → `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`); включение — `ARCHRAG_OBSERVABILITY=true docker compose --profile observability up -d --wait`; без профиля сервисы collector/Prometheus/Grafana не запускаются 🟢 (`platform/docker/docker-compose.yml:200, 235, 474, 484, 502`; `platform/docker/.env.example:51`).
- Collector принимает OTLP/HTTP на `0.0.0.0:4318`, батчит и отдаёт метрики Prometheus-экспортёром на `0.0.0.0:8889` (`resource_to_telemetry_conversion` превращает `service.name`, `service.instance.id` в метки); расширение `health_check` на `13133` 🟢 (`platform/docker/observability/otel-collector.yml:1-28`).
- Prometheus скрейпит только collector (`otel-collector:8889`) каждые 10 с, хранит 2 суток; поэтому `--scale mcp-server=2` работает без service discovery 🟢 (`platform/docker/observability/prometheus.yml:1-8`; `platform/docker/docker-compose.yml:486`; `platform/docker/README.md:138`).
- Готовность collector проверяется косвенно: healthcheck Prometheus требует цель в состоянии `up` (в образе collector нет shell и wget); Grafana ждёт `prometheus: service_healthy` 🟢 (`platform/docker/docker-compose.yml:480-481, 493-499, 519-520`).
- Grafana — единственный публикуемый сервис профиля (`127.0.0.1:${GRAFANA_PORT}`); анонимный доступ и регистрация выключены, пароль админа — Docker secret; источник данных и дашборд создаются provisioning'ом 🟢 (`platform/docker/docker-compose.yml:504-518`; `platform/docker/observability/grafana/provisioning/datasources/prometheus.yml:1-8`; `platform/docker/observability/grafana/provisioning/dashboards/archrag.yml:1-8`).
- Дашборд `archrag-poc`, обновление 10 с, четыре панели: p95 latency по tool (`archrag_mcp_tool_call_milliseconds_bucket`), sync lag по источнику (`archrag_ingestion_sync_lag_seconds`), Error / DLQ rate (`archrag_ingestion_events_total{status=~"QUARANTINED|ERROR"}` и `archrag_ingestion_dlq_open`), throughput проекции (`status="PROJECTED"`) 🟢 (`platform/docker/observability/grafana/dashboards/archrag-poc.json:2-6, 15-144`).
- `measure.sh` требует Docker, bash и python3 на хосте и стенд с профилем observability; параметры `N` (замеры «источник → граф», 20), `M` (событий burst, 200), `K` (вызовов каждого tool, 100), `P` (потоков, 4), `E2E_PATH` (`webhook`/`poll`/`both`, по умолчанию `both`), `E2E_JITTER_MAX` (по умолчанию — интервал polling адаптера) 🟢 (`platform/docker/scripts/measure.sh:1-26`).
- Пороги отказа (`measure.sh` завершается кодом 1): p95 «источник → граф» по webhook > 30 с, по polling > интервал + 5 с; любой tool вернул ошибку; не все `M` событий PROJECTED за 900 с; изменение не дошло до графа за 300 с; webhook не принят адаптером (код вне 2xx); `maxDepth` вне 1..6 (7, 0) принят вместо отказа; DLQ (`archrag_ingestion_dlq_open`) или `QUARANTINED` выросли за прогон 🟢 (`platform/docker/scripts/measure.sh:39, 186, 228, 238, 248-250, 274, 302-305, 329, 376-380`).
- Сырые данные пишутся в `platform/docker/measurements/<UTC-штамп>/` (в `.gitignore`), итоговая markdown-таблица — в stdout 🟢 (`platform/docker/scripts/measure.sh:9, 15`; `.gitignore:32`).
- `measure.sh` меняет состояние стенда: переименовывает `TEAM-PAY` и ИТ-систему `5101` в заглушках и добавляет `M` объектов `EAM-LOAD-<id>-*` (burst идёт без webhook) 🟡 (`platform/docker/scripts/measure.sh:200-232, 253-262`) — Q-17.
- `deployment-commit-check.sh`: ждёт проекции seed-развёртывания `dep-payments-api-prod` и пустой очереди; через MCP (`search_assets`, `find_runtime_footprint`) читает commit; меняет commit в `stub-deploymap` (`POST :8091/control/upsert`, формат `deploymap-poc-1`); ждёт события и очередь; проверяет новый commit в MCP и в Neo4j: ровно одно открытое ребро `DEPLOYED_FROM` с новым commit и ровно одно закрытое (`validTo` задан) со старым — счётчики `1,1,1`; идемпотентен (при повторе commit переключается обратно) 🟢 (`platform/docker/scripts/deployment-commit-check.sh:1-9, 70-133`).
- 🟡 Seed (`EAM-1042`, `5101`, `TEAM-PAY`, два commit-хэша, `dep-payments-api-prod`) зашит в скрипты (Q-17).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Профиль observability: метрики доходят до Prometheus и Grafana | Should | AC-01 |
| RF-02 | Дашборд из четырёх панелей создаётся provisioning'ом | Should | AC-02 |
| RF-03 | `measure.sh`: таблица замеров и код выхода по порогам | Should | AC-03, AC-04 |
| RF-04 | `deployment-commit-check.sh`: смена commit проходит от заглушки до MCP, старая связь закрыта | Should | AC-05, AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Производительность | Задержка «источник → граф» по webhook ≤ 30 с (p95); по polling ≤ интервал + 5 с | `platform/docker/scripts/measure.sh:38-39` | 🟢 |
| Наблюдаемость | Теги метрик ingestion — только `source` и `status`; серия на каждую пару стартует с нулей | `platform/docker/README.md:149` | 🟢 |
| Эксплуатация | Prometheus и collector на хост не публикуются; проверка изнутри `docker compose exec prometheus wget` | `platform/docker/README.md:151-152` | 🟢 |
| Ресурсы | Скрипт-питон в `measure.sh` использует только stdlib | `platform/docker/scripts/measure.sh:4, 31` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (CI: .github/workflows/ci.yml:87-96)
Дано ARCHRAG_OBSERVABILITY=true и профиль observability
Когда docker compose --profile observability up -d --wait, затем smoke.sh и пауза 30 с
Тогда в Prometheus есть серии archrag_mcp_tool_call_milliseconds_count и archrag_ingestion_events_total
Дано Prometheus не вернул результат по одной из серий
Тогда шаг CI завершается кодом 1 ("no series for <метрика>")

# AC-02
Дано Grafana поднята, пароль админа взят из секрета
Когда открыт http://127.0.0.1:3000 (логин admin)
Тогда доступны источник "Prometheus" и дашборд "Arch RAG PoC" в папке "Arch RAG"; анонимный вход запрещён

# AC-03 (measure.sh, позитив)
Дано стенд с профилем observability, очередь журнала пуста
Когда scripts/measure.sh
Тогда таблица содержит p50/p95 по каждому tool (последовательно и при P потоках), p95 «источник → граф» по webhook и polling, throughput burst, sync lag, долю не-PROJECTED, DLQ до/после, строки maxDepth 1..6 и отказы для 7 и 0; код 0

# AC-04 (measure.sh, негатив)
Дано p95 по webhook 31 с (или DLQ вырос, или tool вернул ошибку, или maxDepth=7 принят)
Когда scripts/measure.sh завершается
Тогда секция "Провалы" перечисляет причины, код 1

# AC-05 (deployment-commit-check.sh, позитив)
Дано стенд settled, seed-commit равен COMMIT_A (или COMMIT_B)
Когда scripts/deployment-commit-check.sh
Тогда MCP возвращает противоположный commit, deployedFrom.validFrom изменился, repository.url совпадает с repositoryUrl, счётчики рёбер "1,1,1", печатается OK и код 0; повторный запуск переключает commit обратно

# AC-06 (deployment-commit-check.sh, негатив)
Дано commit в графе не равен ни COMMIT_A, ни COMMIT_B
Когда scripts/deployment-commit-check.sh
Тогда die "unexpected commit …" (seed изменён чем-то другим), код 1
Дано sourceCommit != deployedFrom.commit до изменения
Тогда die "sourceCommit … and deployedFrom.commit … must be equal and set"
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-02 | Should | Критерий 9 PoC (E5.3) |
| RF-03 | Should | Критерий 9 (E5.5); запускается вручную |
| RF-04 | Should | Приёмка UNLOCKER-248; запускается вручную |
| Алерты, SLO, хранение метрик > 2 суток | Won't | gap 3.1, 3.3 |
| Запуск `measure.sh`/`deployment-commit-check.sh` в CI | Won't | Не включены в `docker-stand` (Q-07) |

## Rastreabilidade de Código

| Файл | Назначение | Покрытие |
|------|------------|----------|
| `platform/docker/observability/otel-collector.yml`, `platform/docker/observability/prometheus.yml` | collector, Prometheus | 🟢 |
| `platform/docker/observability/grafana/provisioning/dashboards/archrag.yml`, `platform/docker/observability/grafana/provisioning/datasources/prometheus.yml`, `platform/docker/observability/grafana/dashboards/archrag-poc.json` | Grafana | 🟢 |
| `platform/docker/docker-compose.yml` | сервисы профиля | 🟢 |
| `platform/docker/scripts/measure.sh` | замеры | 🟢 |
| `platform/docker/scripts/deployment-commit-check.sh` | сценарий commit | 🟢 |
