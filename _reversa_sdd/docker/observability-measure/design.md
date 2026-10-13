# observability-measure: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §3, §9. Метрики и теги: [`../../ingestion-service/contracts.md`](../../ingestion-service/contracts.md) §3.

## Interface

| Артефакт | Содержание | Трассировка |
|----------|------------|-------------|
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.140.1`, конфигурация монтируется в `/etc/otelcol/config.yml`; сеть `observability`; без healthcheck | `platform/docker/docker-compose.yml:473-481` 🟢 |
| `prometheus` | `prom/prometheus:v3.7.3`, `--storage.tsdb.retention.time=2d`, том `prometheus-data`, healthcheck «цель `up`» | `platform/docker/docker-compose.yml:483-499` 🟢 |
| `grafana` | `grafana/grafana:12.2.1`, секрет пароля админа, provisioning и дашборды (read-only), порт на loopback, сети `observability` + `obs-edge` | `platform/docker/docker-compose.yml:501-526` 🟢 |
| `measure.sh` | замеры (Python stdlib внутри heredoc); код выхода 0/1 | `platform/docker/scripts/measure.sh:11-380` 🟢 |
| `deployment-commit-check.sh` | сценарий смены commit | `platform/docker/scripts/deployment-commit-check.sh:10-133` 🟢 |

## Fluxo Principal

### Конвейер метрик

`mcp-server` и `ingestion` при `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true` пушат метрики на `http://otel-collector:4318/v1/metrics` (`platform/docker/docker-compose.yml:200-201, 235-236`) → collector (OTLP/HTTP-приёмник → `batch` → Prometheus-экспортёр `:8889`) (`platform/docker/observability/otel-collector.yml:2-28`) → Prometheus (`scrape_interval 10s`, цель `otel-collector:8889`) (`platform/docker/observability/prometheus.yml:2-8`) → Grafana (источник `Prometheus` с `uid: prometheus`, `url: http://prometheus:9090`, дефолтный) (`platform/docker/observability/grafana/provisioning/datasources/prometheus.yml:2-8`). Дашборд `archrag-poc` читается из `/var/lib/grafana/dashboards` (`platform/docker/observability/grafana/provisioning/dashboards/archrag.yml:2-8`) 🟢.

### `measure.sh`

1. Подготовка: каталог `measurements/<UTC>`, `ca.crt` из тома `certs`, интервал polling из `ARCHRAG_POLL_INTERVAL_SECONDS` контейнера `adapter-eam` (по умолчанию 30), экспорт параметров в Python (`platform/docker/scripts/measure.sh:14-27`).
2. Фикстуры: `tools/list`; `search_assets "EAM-1042"` (3 попытки — прогрев), `gid` ИТ-системы `Payments Core`; `search_assets "TEAM-PAY"`; `dlq0 = sum(archrag_ingestion_dlq_open)`, `quar0` (`platform/docker/scripts/measure.sh:135-168`).
3. **A. MCP tools:** на каждый tool 3 прогревочных вызова, `K` последовательных, `K` параллельных (`P` потоков); ошибки → `fail` (`platform/docker/scripts/measure.sh:169-189`).
4. **B. Источник → граф:** для каждого пути (`webhook`: `upsert TEAM` с `notify=true`, проверка `webhookStatus` 2xx; `poll`: случайная задержка до `JITTER`, `upsert` ИТ-системы `5101` в `eam-api-stub`) `N` раз; опрос `get_asset` каждые 0,25 с до совпадения `properties.name` или 300 с; p95 сравнивается с порогом пути (`platform/docker/scripts/measure.sh:191-251`).
5. **C. Throughput:** `M` событий `TEAM` (`EAM-LOAD-<id>-NNNNN`) вставкой в `stub-eam` без webhook, опрос `inbox_event` до `M` PROJECTED за 900 с, окно `min(received_at)…max(updated_at)`, события/с (`platform/docker/scripts/measure.sh:253-285`).
6. **D. Глубина `trace_dependencies`:** `maxDepth` ∈ {1…6, 7, 0, по умолчанию}; 7 и 0 обязаны быть отклонены (`platform/docker/scripts/measure.sh:286-311`).
7. Prometheus: пауза 25 с (OTLP-экспорт), p95 по tools за окно прогона, события по статусам, `dlq1`, `quar1`, максимум sync lag; `dlq1 > dlq0` или `quar1 > quar0` → `fail` (`platform/docker/scripts/measure.sh:312-329`).
8. Отчёт: markdown-таблицы; при наличии `failures` печатается «Провалы» и `sys.exit(1)` (`platform/docker/scripts/measure.sh:334-380`).

### `deployment-commit-check.sh`

Токен `archrag-demo` (scope по умолчанию) через proxy и `ca.crt` → ожидание `PROJECTED` у `dep-payments-api-prod` и пустой очереди (≤ 300 с) → `search_assets "Payments Core"` → `find_runtime_footprint(systemGid, prod)` (`before`: `sourceCommit == deployedFrom.commit`, `validFrom` задан, commit ∈ {A, B}) → upsert противоположного commit в `stub-deploymap` с версией чарта `1.4.3`/`1.4.2` → `wait_event` по `source_version` → очередь пуста → `find_runtime_footprint` (`after`: commit новый, `validFrom` изменился, `repository.url == repositoryUrl`) → Cypher под writer'ом: `open_total, open_new, closed_old` = `1,1,1` (`platform/docker/scripts/deployment-commit-check.sh:19-133`) 🟢. MCP-вызов повторяется до 3 раз с паузой 5 с (индексы Neo4j после старта могут быть не готовы); ошибка tool (`isError`) → краткий текст ≤ 200 символов в stderr и код 1 (`platform/docker/scripts/deployment-commit-check.sh:29-51`) 🟢.

## Fluxos Alternativos

- Нет `python3` на хосте → `measure.sh`: `die "python3 is required on the host"` (`platform/docker/scripts/measure.sh:14`) 🟢.
- Seed не найден в графе → `sys.exit("seed … is not found in the graph")`; это не `failures`, а аварийный выход (`platform/docker/scripts/measure.sh:150-154`) 🟢.
- Несколько путей (`E2E_PATH=both`): отдельные пороги и отдельные строки таблицы; `E2E_PATH` вне `webhook|poll|both` → `die` (`platform/docker/scripts/measure.sh:24-26`) 🟢.
- Очередь не опустела / `QUARANTINED > 0` в `deployment-commit-check.sh` → `die` через `wait_queue_empty` (`platform/docker/scripts/lib.sh:73-83`) 🟢.
- Профиль не включён → `measure.sh` падает на запросах к Prometheus (`docker compose exec prometheus` недоступен) 🟡.
- Повторный запуск `deployment-commit-check.sh` меняет commit на противоположный (проверки симметричны) 🟢 (`platform/docker/scripts/deployment-commit-check.sh:9, 97-102`).

## Dependências

Образы collector/Prometheus/Grafana; метрики `archrag.ingestion.*` ([`../../ingestion-service/design.md`](../../ingestion-service/design.md)) и `archrag.mcp.tool.call` (`mcp-server`); MCP (`ping`, `search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies`, `explain_provenance`) — [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md); заглушки `stub-eam`, `stub-deploymap`, `eam-api-stub` ([`../../flowcharts/demo-launcher.md`](../../flowcharts/demo-launcher.md), [`../../flowcharts/source-stubs.md`](../../flowcharts/source-stubs.md)); матрица влияния №16 (формат deploy map) — `../../traceability/spec-impact-matrix.md:49`.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Метрики push (OTLP) → collector → Prometheus, а не scrape `/actuator/prometheus` | `platform/docker/observability/prometheus.yml:1`; `platform/docker/README.md:138` | 🟡 (ADR нет) |
| Метрики выключены по умолчанию, профиль — opt-in | `platform/docker/docker-compose.yml:200, 474` | 🟢 |
| Два пути «источник → граф» с разными порогами (webhook — гейт критерия 2; polling — страховка) | `platform/docker/scripts/measure.sh:38-39` | 🟢 |
| Burst без webhook, чтобы дубли webhook + polling не искажали throughput | `platform/docker/scripts/measure.sh:258-259` | 🟢 |
| Эквивалентность по сценарию commit проверяется напрямую в Neo4j (не только через MCP) | `platform/docker/scripts/deployment-commit-check.sh:123-131` | 🟢 |
| Сырые измерения хранятся вне Git | `.gitignore:32`; `platform/docker/scripts/measure.sh:9` | 🟢 |

## Riscos e Lacunas

- 🟢 [R.8: ответ владельца, вопрос 14] `measure.sh` и `deployment-commit-check.sh` не входят в CI — Q-07.
- 🟡 Seed зашит в скрипты; `measure.sh` меняет состояние заглушек — Q-17.
- 🟡 Метрика `archrag_ingestion_dlq_open` завышена (не уменьшается после replay), поэтому проверка «DLQ не вырос» корректна как рост, но панель «открытые» вводит в заблуждение (Н-3 `../../tech-debt.md:58`).
- 🟡 Дашборд называет панель «Error / archrag_ingestion_dlq_open rate», README — «Error / DLQ rate» (`platform/docker/observability/grafana/dashboards/archrag-poc.json:79`; `platform/docker/README.md:146`).
- 🟡 Алертов нет (gap 3.1).
