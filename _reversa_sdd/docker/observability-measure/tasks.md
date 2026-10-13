# observability-measure: задачи

## Pré-requisitos
- [ ] Стенд поднят с `ARCHRAG_OBSERVABILITY=true` и профилем `observability` ([`../stand-startup/tasks.md`](../stand-startup/tasks.md))
- [ ] Хост: Docker, bash, python3; для `measure.sh` — доступ к proxy по TLS (`ca.crt` берётся из тома)

## Tarefas

- [ ] T-01: Сервисы профиля (`otel-collector`, `prometheus`, `grafana`; профиль, тома, сети `observability`/`obs-edge`, порт Grafana на loopback, healthcheck'и)
  - Origin: `platform/docker/docker-compose.yml:470-526, 537-541`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-02: Конфигурации collector и Prometheus
  - Origin: `platform/docker/observability/otel-collector.yml:1-28`; `platform/docker/observability/prometheus.yml:1-8`
  - Критерий готовности: Prometheus видит цель `otel-collector:8889` в состоянии `up`
  - Уверенность: 🟢
- [ ] T-03: Provisioning Grafana (источник `Prometheus`, провайдер дашбордов) и дашборд `archrag-poc` (4 панели)
  - Origin: `platform/docker/observability/grafana/provisioning/datasources/prometheus.yml:1-8`; `platform/docker/observability/grafana/provisioning/dashboards/archrag.yml:1-8`; `platform/docker/observability/grafana/dashboards/archrag-poc.json:1-153`
  - Критерий готовности: AC-02
  - Уверенность: 🟢
- [ ] T-04: Включение OTLP-экспорта в `mcp-server` и `ingestion` через `ARCHRAG_OBSERVABILITY`
  - Origin: `platform/docker/docker-compose.yml:200-201, 235-236`; `platform/docker/.env.example:50-51`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-05: `measure.sh` (параметры, фикстуры, секции A–D, Prometheus, отчёт, коды выхода)
  - Origin: `platform/docker/scripts/measure.sh:1-380`
  - Критерий готовности: AC-03, AC-04
  - Уверенность: 🟢
- [ ] T-06: `deployment-commit-check.sh` (токен, `footprint`, upsert в `stub-deploymap`, Cypher `1,1,1`)
  - Origin: `platform/docker/scripts/deployment-commit-check.sh:1-133`
  - Критерий готовности: AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-07: Шаг CI «observability profile» (подъём, `smoke.sh`, пауза 30 с, проверка двух серий в Prometheus)
  - Origin: `.github/workflows/ci.yml:86-96`
  - Критерий готовности: AC-01
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: Шаг CI observability (AC-01) — есть
- [ ] TT-02: `measure.sh` — вручную; результаты в `docs/04-poc-measurements.md`
- [ ] TT-03: 🟢 [R.8: ответ владельца, вопрос 14] `deployment-commit-check.sh` и `measure.sh` не в CI (Q-07)
- [ ] TT-04: 🟡 нет проверки, что панели дашборда возвращают данные (проверяется лишь наличие двух серий)

## Ordem Sugerida
T-02 → T-01 → T-03 → T-04 → T-07 → T-05 → T-06.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-07 (🟢 [R.8: ответ владельца, вопрос 14]); Q-17 (🟡).
