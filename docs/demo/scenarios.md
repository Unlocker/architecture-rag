# Сценарии демо Arch RAG

> Пошаговое руководство ведущего. Данные — вымышленный банк «Nordhaven Bank» ([dataset.md](dataset.md), кейсы К-1…К-11). Скрипты — `platform/docker/demo/NN-*.sh`, эталонные ответы — `platform/docker/demo/expected/`. Контракты tools — [03-interfaces, раздел 2](../03-interfaces.md#2-mcp-сервер). Аудитория: разработчики и архитекторы.

## Содержание

1. [Подготовка стенда и откат](#1-подготовка-стенда-и-откат)
2. [Подготовка агента (Claude Code)](#2-подготовка-агента-claude-code)
3. [Как читать ответ MCP](#3-как-читать-ответ-mcp)
4. Сценарии: [1 поиск](#сценарий-1-поиск-и-карточка-актива) · [2 где развёрнуто](#сценарий-2-где-развёрнута-система) · [3 trace](#сценарий-3-зависимости-trace) · [4 impact](#сценарий-4-impact-отказа-вм) · [5 provenance](#сценарий-5-происхождение-данных-и-конфликты) · [6 свежесть](#сценарий-6-свежесть-и-неразрешённые-ссылки) · [7 доступ](#сценарий-7-безопасность-и-границы) · [8 передеплой](#сценарий-8-передеплой-смена-commit) · [9 вывод из эксплуатации](#сценарий-9-логическое-удаление-и-reconcile) · [10 администрирование](#сценарий-10-администрирование-identity-и-карантин)
5. [Протокол изнутри: MCP Inspector](#4-протокол-изнутри-mcp-inspector)

Сценарии 1–7 только читают и повторяются сколько угодно. Сценарии 8–10 **меняют** стенд и необратимы: повтор — через новый `seed.sh demo`. Порядок показа: 1 → 7, затем 8 → 10.

## 1. Подготовка стенда и откат

Все команды — из `platform/docker`. Нужны Docker, `jq`, `curl`; для агента — Node.js и Claude Code, для Inspector — `npx`.

```bash
cd platform/docker
../../mvnw -B -ntp -f ../../pom.xml package -DskipTests    # jar для образов
cp .env.example .env                                       # значения .env в Git не кладутся
demo/seed.sh demo                                          # около 10 минут
demo/demo-check.sh                                         # проверка, что демо не разошлось с кодом
```

`seed.sh` **сносит** контейнеры и тома проекта Compose (`down -v`; пауза 5 с, `SEED_RESET=0` оставляет). Он грузит источники строго в порядке EAM → SCM → CMDB → deploy map: от порядка зависит маркер `conflicts` в `trace_dependencies` ([dataset.md, п. 7.5](dataset.md#7-требования-к-профилю-заглушек-для-demo2)).

**Контрольные точки перед показом** (всё печатает `seed.sh` и `demo-check.sh`):

| Что | Ожидание |
|---|---|
| Журнал | очередь пуста; в `QUARANTINED` одна запись `dep-card-tokenizer-test-badcommit` (К-11) |
| Кандидаты identity | 2 открытых (К-5а `score=0.60`, К-5б `score=0.80`) |
| Конфликты источников | 2 (К-4а `eamSystemId`, К-4б `platformId`) |
| Граф | 27 `ComputeInstance`, 41 `Deployment`, 27 `Service`, 26 `Repository`, 10 `ITSystem` |

**Откат.** Мутации 08–10 не откатываются. Любой запуск `run-all.sh`/`demo-check.sh` на изменённом стенде завершается кодом 2 с просьбой выполнить `demo/seed.sh demo`. Рекомендация: за час до показа выполнить `seed.sh demo` и `demo-check.sh`, **затем снова `seed.sh demo`** (проверка мутирует стенд), и больше ничего не запускать до показа.

**Если что-то пошло не так:**

| Симптом | Действие |
|---|---|
| Скрипт напечатал `stand is not in the base state` | `demo/seed.sh demo` |
| Первый запрос упал по таймауту (холодный Neo4j) | `mcp_call` повторяет сам до трёх раз; если не помогло — повторить сценарий |
| `401` на всех вызовах агента | токен Keycloak живёт ~5 минут: [обновить](#обновление-токена) |
| Сценарий 9 ничего не закрыл | дождаться пустой очереди (`demo-check.sh --counts` ничего не меняет), иначе новый seed |
| Нет Docker на машине ведущего | показ невозможен вживую: ведущий показывает `expected/*.json` и `docs/demo/graph.html` |

## 2. Подготовка агента (Claude Code)

Живой агент — Claude Code как внешний MCP-клиент. Он подключается к тому же `POST /mcp`, что и скрипты (reverse proxy, TLS, порт `ARCHRAG_PUBLIC_PORT`, по умолчанию 8443).

```bash
demo/agent-setup.sh --check      # initialize + tools/list тем же путём, что у агента; набор tools сверяется с expected/tools-list.json
demo/agent-setup.sh              # печатает 3 строки: export NODE_EXTRA_CA_CERTS=..., claude mcp add ..., срок жизни токена
```

1. **Доверие к сертификату.** Стенд подписан собственным CA. Скрипт копирует его (публичный сертификат, не секрет) в `~/.cache/archrag-demo/ca.crt`. Выполните напечатанную строку `export NODE_EXTRA_CA_CERTS=...` **в том же терминале**, из которого запускается `claude`: Node берёт CA из этой переменной. Без неё агент увидит ошибку TLS.
2. **Регистрация.** Выполните напечатанную команду `claude mcp add --transport http archrag https://127.0.0.1:8443/mcp --header "Authorization: Bearer …"`. Либо `demo/agent-setup.sh --register`: он заменит старую запись `archrag` и сам вызовет `claude mcp`. Заголовок сохраняется в `~/.claude.json`, поэтому после показа: `claude mcp remove archrag`.
3. **Проверка.** Запустите `claude`, команда `/mcp` должна показать `archrag` подключённым и 6 tools (`explain_provenance`, `find_runtime_footprint`, `get_asset`, `ping`, `search_assets`, `trace_dependencies`).

### Обновление токена

Токен выпускает Keycloak стенда (`client_credentials`, клиент `archrag-demo`, scope `architecture.read`, без `architecture.admin`) и живёт около 5 минут. Для долгой беседы:

```bash
demo/agent-setup.sh --register   # заменяет запись archrag свежим токеном
# в claude: /mcp -> переподключить archrag (или перезапустить claude)
```

Токен печатается только в терминал. В репозиторий, лог CI и файлы скриптов он не попадает. Не показывайте экран терминала с токеном на записи.

### Как вести живого агента

- Агент сам вызывает tools; ведущий видит каждый вызов в интерфейсе Claude Code (имя tool и аргументы) и может раскрыть ответ. Просите агента **показать вызванные tools и аргументы**: это и есть демонстрация.
- Агент не знает `gid`. Типичная цепочка — `search_assets` (получить `gid`), затем целевой tool. Это нормально и стоит показать: так же устроены скрипты (`asset_gid`).
- Тексты из источников (описания систем) агент получает как данные. Они не исполняются как инструкции; если агент пересказывает `description`, это цитата, а не команда.
- Агент недетерминирован. Для каждого сценария ниже даны 2–3 формулировки, ожидаемый вызов и порядок **отката**: переформулировать вопрос (подсказать tool или параметр), затем — выполнить скрипт сценария.

Общая схема отката (повторяется в сценариях кратко):

1. Агент выбрал другой tool или параметры → уточнить: «используй tool `X` с параметром `Y`».
2. Не помогло за две реплики → `demo/NN-*.sh` и показать тот же запрос и ответ из скрипта.
3. Агент пытается написать Cypher или сходить в базу → напомнить, что такого tool нет ([ADR 0018](../adr/0018-domain-tools-not-arbitrary-cypher.md)); это тоже часть демонстрации.

## 3. Как читать ответ MCP

Общие правила для всех сценариев ([03, раздел 2.3](../03-interfaces.md#23-общие-правила-ответа)). Ответ tool — JSON-строка в `result.content[0].text`.

| Поле | Как читать |
|---|---|
| `truncated` | `true`: ответ обрезан бюджетом строк, путей или байт. Состав обрезанного результата может меняться между вызовами. Это не ошибка |
| Provenance узла | `lastSeenAt` и `sources[]` (`source`, `sourceType`, `sourceId`, `authority`: `MASTER`/`SUPPLEMENTARY`) |
| Provenance связи | кто её утверждает: `assertedBySource`/`assertedByType`/`assertedById`, время получения записи `sourceFetchedAt` |
| Свежесть | `stale=true`, если `lastSeenAt` (для связи `sourceFetchedAt`) не задан или старше порога `staleAfter` (`PT168H` = 7 суток) |
| Конфликты | у узла в `trace_dependencies`: `conflicts[]` — записи источников, чьи значения расходятся с мастером, и **имена** свойств; в `explain_provenance`: `conflictState` (`OPEN`/`NONE`), `conflictProperties`. Значения свойств в маркерах не отдаются |
| Закрытые факты | действующие узлы и связи — всегда; закрытые (`isCurrent=false`, `validTo`) только в `get_asset` и `explain_provenance` |
| Ошибка tool | HTTP 200, `result.isError=true`, короткий текст без значений запроса |
| Отказ по доступу | не ошибка tool, а HTTP 401/403 |

**Лимиты** ([03, 2.3](../03-interfaces.md#23-общие-правила-ответа)): `maxDepth` 1..6, путей до 50 (`max-paths`), узлов/строк до 500 (`max-nodes`), таймаут шаблона 5 с, ответ до 512 КБ, `search_assets.limit` 1..50 (по умолчанию 10), `query` до 200 символов, тело запроса до 1 МБ.

**Что общее у всех tools** (скажите один раз, дальше только напоминайте):

- Scope `architecture.read` на каждый tool; tool без записи в `tool-scopes` закрыт по умолчанию (403). Роль `archrag-reader` нужна на любой запрос к `/mcp`.
- Только чтение: единственный путь к графу — `executeRead` с сессией `AccessMode.READ`, шаблон перед первым выполнением проверяется `EXPLAIN` (должен быть `READ_ONLY`). Neo4j Community без ролей: read-only обеспечивает приложение, а не СУБД; подсказки `readOnlyHint` защитой не служат.
- Cypher только параметризованный; метки и типы связей — из allowlist. Глубина обхода — проверенное число в диапазоне лимитов.
- **Чего tools сознательно не делают** ([ADR 0018](../adr/0018-domain-tools-not-arbitrary-cypher.md)): не принимают произвольный Cypher (`read_cypher` не включён), не пишут в граф, не дают обход без верхней границы, не отдают значения конфликтующих свойств и payload источников, не склеивают активы автоматически ([ADR 0019](../adr/0019-identity-resolution-no-auto-merge.md)).

Скрипты печатают запрос, ответ и список подсвеченных полей (`truncated`, `provenance`, `stale`, `lastSeenAt`, `conflicts`, `conflictState`, `staleAfter`, `warnings`, `isCurrent`, `deletedAt`) в строках `  * путь = значение`. На TTY подсветка цветная, в CI и в файле ANSI нет.

---

## Сценарий 1. Поиск и карточка актива

**Скрипт:** `demo/01-search-card.sh` · **Мутирует:** нет · **Кейсы:** К-10

**Бизнес-вопрос.** «Что у нас есть по платежам и кто за это отвечает? Найдите систему, не зная ни её идентификатора, ни `gid`».

**Что показываем.** Один вход (`search_assets`) находит актив по коду из EAM, по имени и по домену; `get_asset` раскрывает карточку: свойства, записи источников и связи 1 уровня. Значение вне словаря не теряется.

**Шаги.**

1. `demo/01-search-card.sh` (или шаги ниже у агента).
2. Поиск `EAM-1042` → ровно одна находка, `matchType=EXACT`.
3. Поиск `Card Gateway` с `types=["ITSystem"]` → система первой.
4. Поиск `Risk & Compliance` → системы домена (по тексту `description`).
5. Карточка Payments Core → владелец (`OWNED_BY`) и сервисы (`DECOMPOSED_INTO`).
6. Карточка Fraud Screening (К-10) → в `properties` `criticalityRaw=CRITICAL`, `criticality` отсутствует.

### Разбор MCP-запроса

**`search_assets`**

| | |
|---|---|
| Назначение | Точное совпадение по `gid`/`sourceId`/коду EAM, затем полнотекст по имени и описанию |
| Параметры | `query` (обязателен, 1..200 символов), `types[]` (из `ITSystem`, `Service`, `Repository`, `Team`, `Environment`, `Deployment`, `ComputeInstance`; неизвестная метка — ошибка), `environment` (код), `limit` (1..50, по умолчанию 10) |
| Шаблон | `search_assets` (`graph-query-core`, `AssetTemplates`) |
| Лимиты, `truncated` | `truncated=true`, если кандидатов больше `limit` или сработал бюджет. `limit=51` — не усечение, а ошибка `limit must be in 1..50` |
| Provenance, свежесть | `sources[]` — системы-источники активных записей, `lastSeenAt` у каждой находки |
| Scope, read-only | `architecture.read`, `executeRead` |
| Чего не делает | Полнотекст идёт только по `ITSystem` и `Service`: остальные типы находятся лишь точным совпадением. Не ищет закрытые активы. Не принимает Lucene-синтаксис как запрос к графу |

**`get_asset`**

| | |
|---|---|
| Назначение | Карточка актива по `gid`: свойства, времена, записи источников, действующие связи 1 уровня в обе стороны |
| Параметры | `gid` (UUID, обязателен), `includeRelations` (по умолчанию `true`) |
| Шаблоны | `get_asset` (карточка), `asset_relations` (связи) |
| Лимиты | `truncated` по бюджету связей; `gid` соседей пригодны для следующих вызовов |
| Provenance | `sources[]`: `source`, `sourceType`, `sourceId`, `sourceVersion`, `fetchedAt`, `active`, `authority`; `firstSeenAt`, `lastSeenAt` |
| Чего не делает | Не обходит граф глубже 1 уровня (для этого `trace_dependencies`); не показывает payload источника |

### Ожидаемый ответ (сокращённо)

```jsonc
// search_assets {"query":"EAM-1042"}
{"items":[{"matchType":"EXACT","name":"Payments Core","type":"ITSystem","sources":["EAM"],"score":1000.0}],"truncated":false}

// get_asset (Fraud Screening, 5201) — К-10
{"type":"ITSystem","isCurrent":true,
 "properties":{"name":"Fraud Screening","eamCode":"EAM-3001","platformId":3101,"status":"ACTIVE",
               "criticalityRaw":"CRITICAL","description":"Domain: Risk & Compliance. …"},
 "relations":[{"relationType":"OWNED_BY","direction":"OUT","name":"Risk Team"},
              {"relationType":"DECOMPOSED_INTO","direction":"OUT","name":"fraud-rules"},
              {"relationType":"DECOMPOSED_INTO","direction":"OUT","name":"fraud-scoring"}],
 "sources":[{"source":"EAM","sourceType":"IT_SYSTEM","sourceId":"5201","authority":"MASTER","active":true}],
 "truncated":false}
```

Эталоны: `expected/01-*.json`.

**Что подчеркнуть.**

- Один вход для людей и агентов: код из EAM, имя, домен — всё находится одним tool.
- `CRITICAL` не входит в словарь EAM (`HIGH/MEDIUM/LOW`). Значение **не выброшено и не подогнано**: исходная строка лежит в `criticalityRaw`, нормализованное поле не заполнено ([ADR 0040](../adr/0040-source-dictionary-values-policy.md)).
- Домен — не узел графа, а текст `Domain: …` в `description` ([dataset.md, п. 8.2](dataset.md#8-вопросы-архитектору-и-ограничения)).

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «Найди в архитектурном каталоге систему Payments Core по коду EAM-1042» | `search_assets {"query":"EAM-1042"}` |
| «Какие у нас есть ИТ-системы в домене Risk & Compliance?» | `search_assets {"query":"Risk & Compliance","types":["ITSystem"]}` |
| «Покажи карточку системы Fraud Screening: владелец, сервисы, критичность» | `search_assets` (получить `gid`) → `get_asset {"gid":"<gid>"}` |

Ответ агента: список находок с типами и источниками; для карточки — владелец Risk Team, два сервиса и пометка, что критичность `CRITICAL` вне словаря и сохранена как есть. **Откат:** если агент просит «критичность» и пересказывает как `HIGH` — вернуть к `properties.criticalityRaw`; если выбрал не `search_assets` — назвать tool; затем `demo/01-search-card.sh`.

**Типичные вопросы.**

- *Почему поиск по «Risk & Compliance» находит системы, а не сервисы?* Мы передали `types=["ITSystem"]`; без фильтра в выдачу попадут и сервисы, у которых это слово есть в описании.
- *Можно искать по хосту?* Да, но только точным именем (`vm-pay-01`): полнотекста по `ComputeInstance` нет.
- *Кто ответит за сервис?* Владелец только у системы (`OWNED_BY`); сервис отвечает через `DECOMPOSED_INTO` → система → команда.

---

## Сценарий 2. Где развёрнута система

**Скрипт:** `demo/02-runtime-footprint.sh` · **Мутирует:** нет · **Кейсы:** К-2, К-3, К-8

**Бизнес-вопрос.** «Где и в каких версиях работает Payments Core? Какой commit на проде и чем он отличается от теста? Какие системы делят одну ВМ?»

**Что показываем.** `find_runtime_footprint` собирает цепочку система → сервис → развёртывание → вычислительный узел, по окружениям, с исходным репозиторием и commit из Helm chart.

**Шаги.**

1. Footprint Payments Core без фильтра (К-3): `payments-api` в `dev`, `test`, `prod` с тремя разными commit.
2. То же с `environment=prod` — остаются только prod-развёртывания.
3. Footprint систем Ledger (5102), Settlement Hub (5104), AML Monitoring (5202), Notification Center (5303) с `environment=prod` (К-2): у каждой в `compute` есть `vm-legacy-app-01`.
4. Сервис `svc-partner-gateway` и его развёртывание (К-8): нет связи с системой, `repositoryUrl` и `sourceCommit` на узле есть, связи `DEPLOYED_FROM` нет.

### Разбор MCP-запроса

**`find_runtime_footprint`**

| | |
|---|---|
| Назначение | Где развёрнута система: сервисы → deployments по окружениям → вычислительные узлы (VM, физические серверы) |
| Параметры | `systemGid` (UUID узла `ITSystem`, обязателен), `environment` (код окружения: `dev`, `test`, `prod`; необязателен) |
| Шаблон | `runtime_footprint` |
| Лимиты, `truncated` | Строка результата — тройка (service, deployment, compute); на большой системе `truncated=true`. Сервис без развёртываний в окружении остаётся с пустым `deployments` |
| Provenance | `systemSources[]`, `sources[]` у сервиса, развёртывания, узла; `hasDeployment`, `runsOn`, `deployedFrom` — кто и когда утвердил связь; `deployedFrom.commit` — commit из Helm |
| Свежесть | `systemStale`, `stale` на каждом уровне, `staleAfter`, `warnings[]` (по строке на устаревший узел) |
| Scope, read-only | `architecture.read`, `executeRead` |
| Чего не делает | Принимает только `gid` системы (не сервиса и не хоста). Не следует за `DEPENDS_ON`: что **зависит** от этих сервисов, отвечает `trace_dependencies`. `repository`, `deployedFrom`, `sourceCommit`, `repositoryUrl` пусты, если у развёртывания нет открытой связи `DEPLOYED_FROM` |

### Ожидаемый ответ (сокращённо)

```jsonc
// find_runtime_footprint {"systemGid":"<Payments Core>"}  — К-3
{"systemName":"Payments Core","services":[
  {"name":"payments-api","deployments":[
    {"environment":"dev", "version":"1.5.0-rc1","sourceCommit":"1ffad212…"},
    {"environment":"test","version":"1.4.3",    "sourceCommit":"1b4f0e98…"},
    {"environment":"prod","version":"1.4.2",    "sourceCommit":"9fceb02d…"}]},
  {"name":"payments-router","deployments":[{"environment":"prod","version":"2.3.0"},{"environment":"test","version":"2.3.1"}]},
  {"name":"payments-batch"}, {"name":"payments-orchestrator"}],
 "staleAfter":"PT168H","warnings":[],"truncated":false}

// К-2: у систем 5102, 5104, 5202, 5303 в prod один и тот же хост
//   …"compute":[{"hostname":"vm-legacy-app-01.prod.example.org","state":"RUNNING","type":"VirtualMachine",
//                "runsOn":{"source":"DEPLOYMAP","sourceId":"dep-…-prod"}}]

// К-8: get_asset(dep-partner-gateway-prod)
{"type":"Deployment","properties":{"repositoryUrl":"https://git.example.org/chan/partner-gateway-v2","sourceCommit":"251c1bd9…","eamSystemId":9999,"version":"0.5.0"},
 "relations":[{"relationType":"HAS_DEPLOYMENT","direction":"IN","name":"partner-gateway"},
              {"relationType":"IN_ENVIRONMENT","name":"Production"},{"relationType":"RUNS_ON","name":"vm-chan-03.prod.example.org"}]}
```

Эталоны: `expected/02-*.json`.

**Что подчеркнуть.**

- Commit — факт из Helm chart (deploy map — мастер `Deployment`, [ADR 0009](../adr/0009-deployment-from-deploymap.md), [0029](../adr/0029-helm-chart-system-repo-commit.md)), а не догадка. «Что на проде» и «что на тесте» — один запрос с разным `environment`.
- Общая ВМ видна **из любой** из четырёх систем. Ни одна команда не держит эту картину целиком: её даёт связь графа.
- Неразрешённая ссылка не прячется: развёртывание есть, адрес репозитория и commit на узле есть, а связь с `Repository` не построена, потому что такого репозитория в SCM нет. Когда он появится, связь достроится ([сценарий 6](#сценарий-6-свежесть-и-неразрешённые-ссылки)).

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «Где развёрнут Payments Core и какие версии в каждом окружении?» | `search_assets` → `find_runtime_footprint {"systemGid":"<gid>"}` |
| «Какой commit payments-api сейчас на проде и чем он отличается от тестового?» | то же; можно с `environment` |
| «Какие ИТ-системы сидят на одной ВМ с Ledger?» | `search_assets` (Ledger) → `find_runtime_footprint` с `environment=prod`; для обратной связи по ВМ — `trace_dependencies` в режиме `impact` ([сценарий 4](#сценарий-4-impact-отказа-вм)) |

Ответ агента: таблица «сервис × окружение × версия × commit», хосты. **Откат:** агент передал в `systemGid` имя вместо `gid` → попросить сначала найти `gid`; агент «угадал» commit без вызова → потребовать вызов tool; затем скрипт 02.

**Типичные вопросы.**

- *Почему нет Kubernetes-кластера и namespace?* Они смоделированы, но не наполняются ([ADR 0011](../adr/0011-namespace-cluster-modeled-not-populated.md)); развёртывания привязаны к ВМ.
- *Откуда код окружения `prod`?* Из записи `ENVIRONMENT` (ключ — код) и поля `environment` развёртывания в deploy map.
- *Почему `find_runtime_footprint` не находит `partner-gateway`?* У сервиса нет системы (`EAM-9999` в EAM нет), а tool стартует от системы.

---

## Сценарий 3. Зависимости: trace

**Скрипт:** `demo/03-trace.sh` · **Мутирует:** нет · **Кейсы:** К-1, К-4

**Бизнес-вопрос.** «От чего зависит интернет-банк? Покажите цепочку и объясните каждый шаг. Не утонем ли мы в ответе?»

**Что показываем.** Обход `trace_dependencies` от сервиса `ib-web` по `DEPENDS_ON`: цепочка из 7 рёбер упирается в границу глубины 6. Широкий обход без фильтра типов упирается в лимит путей. Маркеры конфликта на узлах.

**Шаги.**

1. Обход от `ib-web`, `relationTypes=["DEPENDS_ON"]`, `maxDepth=6`: достигнут `fraud-scoring` (6 рёбер), `fraud-rules` (7-е ребро) **за границей** глубины.
2. Тот же обход без `relationTypes` и `maxDepth`: `truncated=true`, ровно 50 путей.
3. Обход от развёртывания `dep-settlement-recon-prod` (`maxDepth=1`): у узла `Deployment` маркер `conflicts` по `eamSystemId` (К-4а).
4. Обход от Notification Center (5303), `relationTypes=["DECOMPOSED_INTO"]`, `maxDepth=1`: маркер по `platformId` (К-4б); `sms-dispatcher` помечен `stale` (К-7).

### Разбор MCP-запроса

**`trace_dependencies`, режим `trace`**

| | |
|---|---|
| Назначение | Обход графа от актива по allowlist типов связей с объяснимыми путями |
| Параметры | `gid` (UUID, обязателен); `mode` (`trace` по умолчанию); `direction` (`downstream` по умолчанию — по направлению связей, или `upstream`); `maxDepth` (1..6, по умолчанию 6); `relationTypes[]` (`DEPENDS_ON`, `DECOMPOSED_INTO`, `HAS_DEPLOYMENT`, `RUNS_ON`, `HOSTED_ON`, `OWNED_BY`, `DEPLOYED_FROM`; по умолчанию все) |
| Шаблоны | `trace_downstream`, `trace_upstream` (по `direction`); `get_asset` — проверка существования стартового узла при пустом обходе |
| Лимиты, `truncated` | `maxDepth` 0 или 7 → **ошибка** `maxDepth must be in 1..6` (без молчаливого усечения); путей до 50 (`max-paths`), иначе `truncated=true`; узлов до 500; таймаут 5 с. Обход без верхней границы невозможен: глубина в шаблоне — проверенное число |
| Provenance связей | `relations[]`: `type`, `from`, `to`, `validFrom`, `assertedBy*`, `sourceFetchedAt`, `sourceActive`, `stale`. Шаг пути `i` объясняется `relations[i]` этого пути (ключ `(type, from, to)`) |
| Конфликты | `nodes[].conflicts[]`: запись-диссидент (`source`, `sourceType`, `sourceId`) и имена спорных `properties[]`; значения не отдаются |
| Scope, read-only | `architecture.read`, тот же scope, что у `impact` |
| Чего не делает | Не идёт глубже 6 и не отдаёт «все пути» при ветвлении; не строит новые связи; `DEPLOYED_FROM` доступен только в `trace`. Все `DEPENDS_ON` из EAM приходят `stale=true`: связь утверждена записью без узла и не несёт `sourceFetchedAt` — известное ограничение ([dataset.md, п. 8.4](dataset.md#8-вопросы-архитектору-и-ограничения)). Для показа свежести используйте [сценарий 6](#сценарий-6-свежесть-и-неразрешённые-ссылки) |

### Ожидаемый ответ (сокращённо)

```jsonc
// {"gid":"<ib-web>","relationTypes":["DEPENDS_ON"],"maxDepth":6}
{"mode":"trace","direction":"downstream","staleAfter":"PT168H",
 "nodes":[{"label":"Service","name":"ib-web"},{"name":"ib-api"},{"name":"payments-api"},{"name":"payments-router"},
          {"name":"card-gateway"},{"name":"card-authorizer"},{"name":"fraud-scoring"}, …],   // fraud-rules отсутствует
 "relations":[{"type":"DEPENDS_ON","assertedBySource":"EAM","assertedByType":"SERVICE_DEPENDENCY","sourceFetchedAt":null,"stale":true}, …],
 "paths":[ … 13 путей … ],"affected":[],"truncated":false}

// без relationTypes: сервис → развёртывание → хост ветвится
{"truncated":true,"paths":50}                         // эталон хранит только эти два поля

// trace от dep-settlement-recon-prod, maxDepth=1
{"nodes":[{"label":"Deployment","name":"settlement-recon",
           "conflicts":[{"source":"DEPLOYMAP","sourceType":"DEPLOYMENT","sourceId":"dep-settlement-recon-prod","properties":["eamSystemId"]}]}, …]}
```

Эталоны: `expected/03-*.json`. Широкий обход хранит в эталоне только `truncated` и число путей: **какие** 50 путей вошли, не гарантируется.

**Что подчеркнуть.**

- Стоимость запроса ограничена **заранее**: глубина, число путей, число узлов, время. Агент не может положить базу вопросом «покажи всё».
- Граница — ошибка, а не тихое усечение: агент узнаёт, что запросил недопустимое, и может исправиться. Усечение же — честный признак `truncated`.
- Каждый шаг пути объясним: у связи есть источник и время.

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «От каких сервисов зависит ib-web, цепочкой зависимостей?» | `search_assets` (`ib-web`, `types:["Service"]`) → `trace_dependencies {"gid":"<gid>","relationTypes":["DEPENDS_ON"]}` |
| «Проследи зависимости ib-web на максимальную глубину и скажи, на каком сервисе цепочка заканчивается» | `trace_dependencies` с `maxDepth=6`; агент должен заметить, что `fraud-rules` за границей, и спросить про другой старт (`fraud-scoring`) |
| «Покажи вообще всё, что связано с ib-web» | `trace_dependencies` без фильтра → `truncated=true`; агент должен сказать, что ответ усечён, и предложить сузить `relationTypes` |

**Откат:** агент не указал `relationTypes` и утонул в хостах → «ограничь связями `DEPENDS_ON`»; агент попробовал `maxDepth=10` → получит ошибку (это можно показать намеренно); затем скрипт 03.

**Типичные вопросы.**

- *Почему максимум 6?* Это потолок сервера (`archrag.query.limits.max-depth`); запрос выше потолка отклоняется ошибкой.
- *Что значит «50 путей»?* `max-paths`; при ветвлении набор путей между вызовами может отличаться.
- *Почему у всех `DEPENDS_ON` `stale=true`?* См. ограничение выше: у них нет `sourceFetchedAt`.

---

## Сценарий 4. Impact отказа ВМ

**Скрипт:** `demo/04-impact.sh` · **Мутирует:** нет · **Кейсы:** К-1, К-2

**Бизнес-вопрос.** «Если упадёт эта виртуальная машина, что пострадает? Какие системы и какие бизнес-сервисы затронуты, и по какой цепочке?»

**Что показываем.** Режим `impact` идёт от вычислительного узла вверх против стрелок до затронутых сервисов и систем, с фильтром по окружению.

**Шаги.**

1. Отказ `vm-risk-02` (prod): затронут `fraud-rules`, а выше по `DEPENDS_ON` — `payments-router` и далее; системы Fraud Screening, Card Gateway, Payments Core и Settlement Hub (К-1).
2. Тот же старт с `environment=test`: затронутых нет — prod-ВМ не влияет на тестовое окружение.
3. Отказ `vm-legacy-app-01` (К-2): затронуты Ledger, Settlement Hub, AML Monitoring, Notification Center и, через зависимости, Mobile Banking.

### Разбор MCP-запроса

**`trace_dependencies`, режим `impact`**

| | |
|---|---|
| Назначение | Что затронет отказ актива: путь вверх (против стрелок) до `Service` и `ITSystem`, затронутые перечислены в `affected` |
| Параметры | `gid` (хост, обязателен); `mode="impact"` (регистр не важен); `direction` — только `upstream` (по умолчанию в `impact`; `downstream` → ошибка `direction must be upstream for mode impact`); `maxDepth` 1..6; `relationTypes[]` из `RUNS_ON`, `HOSTED_ON`, `HAS_DEPLOYMENT`, `DECOMPOSED_INTO`, `DEPENDS_ON`; `environment` (`Environment.code`, до 128 символов, только для `impact`) |
| Шаблон | `trace_impact` |
| Фильтр окружения | Каждый `Deployment` на пути обязан быть в этом окружении; путь без `Deployment` фильтр проходит. В `trace` параметр отвергается: `environment is supported only for mode impact` |
| Лимиты, `truncated` | как в `trace`: до 50 путей, до 500 узлов, глубина 1..6 |
| Provenance, свежесть | как в `trace`: `relations[]` с источником и временем, `staleAfter` |
| Scope, read-only | `architecture.read` |
| Чего не делает | Не оценивает ущерб и не ранжирует по критичности (она в карточке системы, [сценарий 1](#сценарий-1-поиск-и-карточка-актива)); не предсказывает отказ — это обход графа по фактам; не идёт вниз; `HOSTED_ON` ничего не добавляет, пока физические серверы не связаны с ВМ ([dataset.md, п. 8.6](dataset.md#8-вопросы-архитектору-и-ограничения)) |

### Ожидаемый ответ (сокращённо)

```jsonc
// {"gid":"<vm-risk-02>","mode":"impact","direction":"upstream"}
{"mode":"impact","direction":"upstream",
 "affected":[ /* 13 gid: сервисы и системы */ ],
 // ITSystem среди нод: Card Gateway, Fraud Screening, Payments Core, Settlement Hub
 "paths":[ /* 23 пути вида ВМ → Deployment → Service → … */ ],"truncated":false}

// то же с environment=test
{"affected":[],"paths":[],"truncated":false}

// vm-legacy-app-01 → 11 затронутых; ITSystem: AML Monitoring, Ledger, Mobile Banking, Settlement Hub, Notification Center
```

Эталоны: `expected/04-*.json`.

**Что подчеркнуть.**

- Сквозной путь «железо → развёртывание → сервис → система → зависящие сервисы» собран из **четырёх** источников (CMDB, deploy map, SCM, EAM). Ни один из них в одиночку не ответит.
- Общая ВМ `vm-legacy-app-01` — типичный «неочевидный общий отказ»: четыре системы разных команд.
- Фильтр `environment` — защита от ложной тревоги: prod-хост не пугает тест.

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «Что сломается, если упадёт vm-legacy-app-01?» | `search_assets` (`vm-legacy-app-01`, `types:["ComputeInstance"]`) → `trace_dependencies {"gid":"<gid>","mode":"impact"}` |
| «Какие системы банка затронет отказ хоста vm-risk-02 в проде? Покажи цепочку» | `trace_dependencies {"mode":"impact","direction":"upstream"}` |
| «А в тестовом окружении?» | `trace_dependencies` с `environment="test"` |

**Откат:** агент использовал `mode=trace` → уточнить «режим impact»; агент передал `direction=downstream` → получит ошибку; агент не нашёл ВМ по короткому имени → подсказать точное `vm-risk-02`; затем скрипт 04.

**Типичные вопросы.**

- *Почему в списке затронутых Settlement Hub, если на этой ВМ нет его сервисов?* Через зависимость `settlement-engine → card-gateway`; путь виден в `paths`.
- *Это SLA-расчёт?* Нет: это достижимость по фактам из мастер-систем.
- *Почему `affected` — `gid`, а не имена?* Имена и метки — в `nodes[]`; `affected` ссылается на них.

---

## Сценарий 5. Происхождение данных и конфликты

**Скрипт:** `demo/05-provenance-conflicts.sh` · **Мутирует:** нет · **Кейсы:** К-4а, К-4б, К-9, К-10

**Бизнес-вопрос.** «Откуда это известно? Источники противоречат друг другу — кому верить?»

**Что показываем.** `explain_provenance` отвечает, какие записи стоят за активом, кто мастер по матрице авторитетности и где есть открытый конфликт.

**Шаги.**

1. Развёртывание `dep-settlement-recon-prod` (К-4а/К-9): Helm chart называет систему `5102`, а по SCM сервис принадлежит `5104`. `conflictState=OPEN`, `conflictProperties=["eamSystemId"]`.
2. Карточка того же развёртывания: оно привязано к сервису `settlement-recon`.
3. Система Notification Center (К-4б): её `platform_id=3101`, у решения Messaging Hub — `3103`; `conflictProperties=["platformId"]`.
4. Fraud Screening, `property="criticality"` (К-10): мастер — EAM, конфликта нет.

### Разбор MCP-запроса

**`explain_provenance`**

| | |
|---|---|
| Назначение | Откуда взят актив: записи источников, признак авторитетности по матрице, открытые конфликты |
| Параметры | `gid` (UUID, обязателен); `property` (имя свойства по шаблону `^[A-Za-z][A-Za-z0-9_]{0,63}$`, сужает мастеров и конфликты до него) |
| Шаблон | `explain_provenance` |
| Лимиты | `truncated` отсутствует в ответе; записи ограничены числом источников узла; таймаут 5 с |
| Provenance | `records[]`: `source`, `sourceType`, `sourceId`, `sourceVersion`, `fetchedAt`, `contentHash`, `confidence`, `authority` (`MASTER`/`SUPPLEMENTARY`), `authoritative`, `active`, `deletedAt`, `conflictProperties[]`; `authorities[]` — мастера для узла или свойства |
| Конфликты | `conflictState` (`OPEN`/`NONE`). Активные записи идут первыми, tombstone-записи (`active=false`) тоже возвращаются |
| Scope, read-only | `architecture.read` |
| Чего не делает | **Не раскрывает значения** конфликтующих свойств, только имена, чтобы не отдавать данные диссидента; не разрешает конфликт и не выбирает победителя (мастер задан матрицей, [ADR 0008](../adr/0008-authority-matrix-field-level.md)); возвращает закрытый актив с `isCurrent=false`, а не ошибку |

### Ожидаемый ответ (сокращённо)

```jsonc
// explain_provenance(dep-settlement-recon-prod)
{"type":"Deployment","authorities":["DEPLOYMAP"],"conflictState":"OPEN","isCurrent":true,
 "records":[{"source":"DEPLOYMAP","sourceType":"DEPLOYMENT","sourceId":"dep-settlement-recon-prod",
             "authority":"MASTER","authoritative":true,"active":true,"confidence":1.0,
             "conflictProperties":["eamSystemId"]}]}

// explain_provenance(Fraud Screening, property="criticality")
{"type":"ITSystem","property":"criticality","authorities":["EAM"],"conflictState":"NONE",
 "records":[{"source":"EAM","sourceId":"5201","authority":"MASTER","authoritative":true,"conflictProperties":[]}]}
```

Эталоны: `expected/05-*.json`. Конфликт K-4б — тот же формат с `sourceId=5303` и `conflictProperties=["platformId"]`.

**Что подчеркнуть.**

- Граф **не молчит о расхождении** между источниками: он помечает, а не выбирает тихо. Конфликт виден и в `trace_dependencies` (маркер `conflicts`), и в консоли (раздел «Конфликты»).
- Источник истины определён **для каждого поля**: `Deployment` — deploy map, система и платформа — EAM, сервис — SCM.
- Это пример конфликта связей (`kind=RELATION`). Конфликт значения свойства между источниками на текущих мапперах не возникает, он покрыт тестом `SourceConflictIT` ([dataset.md, п. 8.1](dataset.md#8-вопросы-архитектору-и-ограничения)) — отвечайте так на вопрос из зала про `criticality` CMDB против EAM.

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «Откуда известно, к какой системе относится развёртывание settlement-recon в проде, и нет ли противоречий?» | `search_assets` (`dep-settlement-recon-prod`, `types:["Deployment"]`) → `explain_provenance {"gid":"<gid>"}` |
| «Есть ли расхождения по платформе у Notification Center?» | `search_assets` → `explain_provenance {"gid":"<gid>"}` |
| «Какой источник мастер для критичности Fraud Screening?» | `explain_provenance {"gid":"<gid>","property":"criticality"}` |

Ответ агента: конфликт открыт по свойству `eamSystemId`, значения источников не названы (их нет в ответе), мастер — deploy map. **Откат:** агент пытается назвать «значение из Helm» — это домысел, в ответе значений нет; направить на `get_asset` (`properties.eamSystemId`) для значения на узле; затем скрипт 05.

**Типичные вопросы.**

- *Кто и как закрывает конфликт?* Исправлением в источнике (либо, для identity, оператором, [сценарий 10](#сценарий-10-администрирование-identity-и-карантин)); граф сам не выбирает.
- *Почему значения не показываются?* Это данные диссидента из другой системы; детали смотрит человек в консоли.
- *Что такое `confidence`?* Достоверность записи источника (1.0 для мастера в демо).

---

## Сценарий 6. Свежесть и неразрешённые ссылки

**Скрипт:** `demo/06-freshness-unresolved.sh` · **Мутирует:** нет · **Кейсы:** К-7, К-8

**Бизнес-вопрос.** «Можно ли доверять тому, что я вижу? Нет ли в ответе устаревшего?»

**Что показываем.** Устаревшие записи помечены, а не спрятаны; ссылка на несуществующую сущность оформлена как отложенная, а не потеряна.

**Шаги.**

1. Footprint Notification Center (К-7): три предупреждения `stale` (сервис `sms-dispatcher`, его dev-развёртывание, ВМ `vm-dev-legacy-01`), `staleAfter=PT168H`, dev-развёртывание помечено `stale=true`.
2. Поиск `vm-dev-legacy-01`: найден точным совпадением, свежесть смотрим в карточке.
3. Карточка `svc-sms-dispatcher`: актив действующий (`isCurrent=true`, `deletedAt=null`), но `lastSeenAt` старше порога.
4. Карточка `svc-partner-gateway` (К-8): связи `DECOMPOSED_INTO` нет, ссылка на `EAM-9999` отложена.

### Разбор MCP-запроса

Используются `find_runtime_footprint`, `get_asset`, `search_assets` ([сценарии 1 и 2](#сценарий-1-поиск-и-карточка-актива)); здесь важны поля свежести.

| | |
|---|---|
| Свежесть | `stale=true`, если `lastSeenAt` (для связи `sourceFetchedAt`) не задан или старше `stale-after` (`P7D`, в ответах `PT168H`). Порог отдают `find_runtime_footprint` и `trace_dependencies` в поле `staleAfter` |
| `warnings[]` | `find_runtime_footprint`: по строке на каждый устаревший узел (`stale: Service … lastSeenAt=…`) |
| `lastSeenAt` | время последнего получения записи источника; берётся из поля `updatedAt` изменения в источнике, а не из времени запроса |
| Отложенная связь | на уровне графа `PendingRelation` ждёт целевой сущности по alias; в MCP-ответе проявляется **отсутствием** связи (`relations` без `DECOMPOSED_INTO`; `repository=null`, `deployedFrom=null`) |
| Scope, read-only | `architecture.read` |
| Чего не делает | Не удаляет и не скрывает устаревшее: решение «доверять или нет» остаётся за потребителем. Не сообщает отложенные ссылки списком — их видно по отсутствию связи; отсутствие связи ≠ ошибка |

### Ожидаемый ответ (сокращённо)

```jsonc
// find_runtime_footprint(Notification Center)
{"staleAfter":"PT168H",
 "warnings":["stale: Deployment asset:sms-dispatcher lastSeenAt=…",
             "stale: Service asset:sms-dispatcher lastSeenAt=…",
             "stale: VirtualMachine VirtualMachine:vm-dev-legacy-01.dev.example.org lastSeenAt=…"],
 "services":[{"name":"sms-dispatcher","stale":true},{"name":"notification-gateway","stale":false}]}

// get_asset(svc-partner-gateway)
{"type":"Service","isCurrent":true,"relations":[ /* нет DECOMPOSED_INTO */ ]}
```

Эталоны: `expected/06-*.json`.

**Что подчеркнуть.**

- Свежесть — свойство **ответа**, а не фоновая уборка: агент и человек видят предупреждение рядом с данными.
- Неразрешённая ссылка не теряется молча и не создаёт «висячий» узел: связь достроится, когда появится система `EAM-9999` или репозиторий в SCM.
- Различие `stale` и «закрыт»: устаревший актив ещё действует, закрытый (`isCurrent=false`) — нет ([сценарий 9](#сценарий-9-логическое-удаление-и-reconcile)).

**Живой агент.**

| Формулировки | Ожидаемый вызов |
|---|---|
| «Что развёрнуто для Notification Center, и всё ли там свежее?» | `search_assets` → `find_runtime_footprint {"systemGid":"<gid>"}` |
| «Есть ли у нас устаревшие записи по сервису sms-dispatcher?» | `search_assets` → `get_asset` (смотреть `lastSeenAt`) |
| «К какой системе относится сервис partner-gateway?» | `search_assets` → `get_asset`; агент должен сообщить, что системы нет |

**Откат:** агент не упомянул `warnings` → «перечисли предупреждения о свежести из ответа»; агент придумал владельца для `partner-gateway` → показать пустые `relations`; затем скрипт 06.

**Типичные вопросы.**

- *Почему именно 7 суток?* Настройка `archrag.query.stale-after`; на демо — `P7D`.
- *Почему у `trace_dependencies` все `DEPENDS_ON` устарели?* Ограничение: см. [сценарий 3](#сценарий-3-зависимости-trace).
- *Что будет, если появится `EAM-9999`?* Отложенная связь `DECOMPOSED_INTO` достроится ([dataset.md, К-8](dataset.md#5-кейсы--данные--сценарий)).

---

## Сценарий 7. Безопасность и границы

**Скрипт:** `demo/07-access.sh` · **Мутирует:** нет

**Бизнес-вопрос.** «Агент получает доступ к архитектуре банка. Что он может испортить, прочитать лишнее или положить?»

**Что показываем.** Отказы по токену и по tool, ошибки валидации на границах, `readOnlyHint` у всех tools.

**Шаги.**

1. `tools/list` без токена → HTTP 401.
2. `tools/list` с токеном, подпись которого испорчена → 401.
3. `tools/call` для tool, которому не назначен scope (`upsert_asset`), → 403 (закрыт по умолчанию).
4. `tools/list` с корректным токеном → 200.
5. `trace_dependencies` с `maxDepth=7` и `0` → `maxDepth must be in 1..6`.
6. `search_assets` с `limit=51` → `limit must be in 1..50`.
7. `get_asset` с `gid="not-a-uuid"` → `gid must be a UUID`.
8. `tools/list`: у каждого tool `readOnlyHint=true`, `destructiveHint=false`.

### Разбор

| Аспект | Как устроено |
|---|---|
| Аутентификация | OAuth2 resource server: JWT проверяется на каждый запрос (подпись, `issuer`, `audience`). Без проверки токена доступны только `/actuator/health/**` и `/.well-known/oauth-protected-resource/**` |
| Отказ | без токена или невалидный → 401 с `WWW-Authenticate: Bearer resource_metadata=…`; нет scope → 403 с `error="insufficient_scope", scope="architecture.read"` |
| Scope | `architecture.read` на каждом tool. Spring AI MCP starter сам аутентификацию не включает: scope проверяет `ToolScopeFilter`, tool без записи в `archrag.mcp.security.tool-scopes` закрыт по умолчанию |
| Read-only | `executeRead`, сессия `AccessMode.READ`, `EXPLAIN` обязан дать `READ_ONLY`. MCP читает граф под учёткой `archrag_reader`, но ролей в Neo4j Community нет: read-only обеспечивает приложение |
| Границы | `maxDepth` 1..6, `limit` 1..50, `query` ≤ 200, тело ≤ 1 МБ (иначе 413), невалидный JSON → 400. Нарушение границы — ошибка, а не молчаливое усечение |
| Аудит | каждый `tools/call` → запись в `archrag.audit.mcp` и метрика `archrag.mcp.tool.call` (теги `tool`, `decision`: `ALLOWED`, `DENIED_SCOPE`, `DENIED_UNKNOWN_TOOL`, `TIMEOUT`, `ERROR`); токены и аргументы в логи и теги не попадают |
| Недоверенные тексты | описания и документы из источников — данные, не инструкции; они не исполняются и не логируются целиком |

**Ограничение стенда.** Отказ «токен без scope» (`insufficient_scope`) на стенде получить нельзя: Keycloak всегда выдаёт клиенту `archrag-demo` default scope `architecture.read`. Его проверяют тесты mcp-server (`ToolScopeFilter`); на показе его заменяет отказ по tool без scope (п. 3).

### Ожидаемый ответ

```text
tools/list без токена                   -> HTTP 401
tools/list, испорченная подпись         -> HTTP 401
tools/call upsert_asset                 -> HTTP 403
tools/list, корректный токен            -> HTTP 200
trace_dependencies maxDepth=7           -> "maxDepth must be in 1..6"
search_assets limit=51                  -> "limit must be in 1..50"
get_asset gid="not-a-uuid"              -> "gid must be a UUID"
tools/list                              -> 6 tools, у каждого readOnlyHint=true, destructiveHint=false
```

Эталоны: `expected/07-*.json`, `expected/tools-list.json`.

**Что подчеркнуть.**

- Нет tool, который пишет: `upsert_asset` в ответе 403 именно потому, что его не существует и scope ему не назначен. Мутации графа делает только ingestion, и только по событиям мастер-систем; MCP не получает writer-учётных данных.
- Нет произвольного Cypher: поверхность атаки — 6 фиксированных шаблонов с параметрами.
- Подсказки `readOnlyHint` — для клиента; защита — в `executeRead`.

**Живой агент.** Сценарий демонстрируется скриптом: у агента нет способа послать запрос без токена. Допустимо спросить агента: «Создай в каталоге новый сервис» — он ответит, что подходящего tool нет (запись недоступна). **Откат:** если агент пытается обойти — напомнить об ограничении, показать скрипт 07.

**Типичные вопросы.**

- *Токен агента можно использовать для admin?* Нет: scope `architecture.admin` не выдаётся клиенту агента, а admin REST публикуется только внутри сети стенда.
- *Что если агент подставит Cypher в `query`?* Это обычная строка поиска: параметр, а не часть запроса.
- *Токен агента — это пользователь?* Нет, это сервисный клиент `archrag-demo` с ролью `archrag-reader`; пользовательский вход есть только в админ-консоли.

---

## Сценарий 8. Передеплой: смена commit

**Скрипт:** `demo/08-redeploy.sh` · **Мутирует:** да (необратимо) · **Кейс:** К-3 (история)

**Бизнес-вопрос.** «Команда выкатила новую версию. Как быстро это увидит агент и можно ли восстановить, что было раньше?»

**Что показываем.** Цепочка источник → адаптер → журнал → граф → MCP на одном изменении.

**Шаги.**

1. `find_runtime_footprint` для Card Gateway (`prod`): `card-gateway` версии 4.0.2, `deployedFrom.commit` равен `sourceCommit`.
2. Скрипт имитирует `helm upgrade`: в заглушку deploy map приходит `dep-card-gateway-prod` с `chartVersion=4.0.3` и новым commit (`sha1("card-gateway@4.0.3")`).
3. Ожидание события в журнале и пустой очереди.
4. Тот же запрос: версия 4.0.3, новый commit.
5. Проверка графа: у `Deployment` одно открытое ребро `DEPLOYED_FROM` с новым commit и одно закрытое со старым.

### Разбор

MCP-запрос — тот же `find_runtime_footprint` ([сценарий 2](#сценарий-2-где-развёрнута-система)), вызванный до и после.

| Аспект | Как устроено |
|---|---|
| Идемпотентность | дедупликация по `source + eventId`; событие с `sourceVersion` ниже применённого получает `IGNORED_OLD_VERSION` и не применяется ([ADR 0033](../adr/0033-source-version-numeric-idempotency.md)) |
| История | прежнее ребро `DEPLOYED_FROM` закрывается (`validTo`), открывается новое с новым `commit` ([ADR 0028](../adr/0028-structural-relations-temporal.md)); MCP отдаёт только открытое |
| Порядок | checkpoint и high-water mark сдвигаются только после коммита транзакции Neo4j |
| Webhook | только триггер: данные забирает адаптер ([ADR 0039](../adr/0039-webhook-is-trigger-only.md)) |
| Чего MCP не делает | Не показывает закрытые связи и прежний commit: историю связей через MCP не получить; `get_asset`/`explain_provenance` показывают только закрытый **актив**, не закрытую связь |

### Ожидаемый ответ (сокращённо)

```jsonc
// до
{"name":"card-gateway","environment":"prod","version":"4.0.2","sourceCommit":"<c1>","deployedFrom":{"commit":"<c1>"}}
// после
{"name":"card-gateway","environment":"prod","version":"4.0.3","sourceCommit":"<c2>","deployedFrom":{"commit":"<c2>"}}
// в графе: DEPLOYED_FROM (open_total,open_new,closed_old) = 1,1,1
```

Эталоны: `expected/08-footprint-before.json`, `08-footprint-after.json`.

**Что подчеркнуть.** Агент отвечает по состоянию, которое отстаёт от мастера на время цикла адаптера (опрос раз в 30 с и webhook-триггер). Изменение не затирает прошлое: оно закрывается, а не перезаписывается.

**Живой агент.** Удобная связка: агент отвечает «Какая версия card-gateway сейчас в проде?» **до** запуска скрипта (4.0.2), затем ведущий запускает `demo/08-redeploy.sh` в соседнем терминале и просит агента повторить вопрос (4.0.3). Вызов: `find_runtime_footprint` с `environment=prod`. **Откат:** агент ответил из памяти беседы — «обнови данные запросом к tool»; не сработало — скрипт покажет «до» и «после» сам.

**Типичные вопросы.**

- *Сколько занимает путь до графа?* Ограничен циклом опроса адаптера; на стенде скрипт ждёт появления события в журнале до 5 минут.
- *Что, если события придут не по порядку?* Старая версия получит `IGNORED_OLD_VERSION`.
- *Можно ли посмотреть прежний commit через агента?* Нет, через MCP — нет ([ADR 0018](../adr/0018-domain-tools-not-arbitrary-cypher.md): новый вопрос — новый шаблон).

---

## Сценарий 9. Логическое удаление и reconcile

**Скрипт:** `demo/09-lifecycle.sh` · **Мутирует:** да (необратимо) · **Кейс:** К-6

**Бизнес-вопрос.** «Сервис вывели из эксплуатации. Как это попадает в каталог и не оставит ли каталог мёртвые связи?»

**Что показываем.** Исчезновение записи из источника не удаляет узел: после полного snapshot узел закрывается логически, связи получают `validTo`, поиск и обходы его больше не видят.

**Шаги.**

1. До: `legacy-statements` действует, у него есть связи; impact отказа `vm-legacy-app-02` затрагивает `sms-dispatcher` и `legacy-statements`.
2. Скрипт убирает из источников `svc-legacy-statements`, `repo-legacy-statements` (SCM), `dep-legacy-statements-ledger-reporting` (EAM), `dep-legacy-statements-prod` (deploy map).
3. Запускает snapshot (reconcile) SCM, EAM и deploy map и ждёт пустой очереди.
4. После: `search_assets` не находит сервис; `get_asset` по сохранённому `gid` возвращает `isCurrent=false`, `deletedAt` и неактивные записи источников; развёртывание тоже закрыто.
5. `impact` по `vm-legacy-app-02` и `trace` по `ledger-reporting` его больше не видят.
6. В графе у сервиса, репозитория и развёртывания нет открытых связей.
7. К-7 сохранился: `sms-dispatcher` всё ещё `stale` (snapshot не обновил `lastSeenAt` старых записей).

### Разбор MCP-запроса

Используются `get_asset`, `search_assets`, `trace_dependencies` до и после.

| | |
|---|---|
| Правило удаления | Удалять можно только после полного snapshot: `Reconciler` обрабатывает missing set лишь по маркеру `snapshot-complete`, посреди загрузки удаление запрещено. На массовое исчезновение (более половины активных записей источника, `SNAPSHOT_MASS_CLOSE_LIMIT`) действует защита: маркер уходит в карантин, ничего не закрывается |
| Логическое удаление | Tombstone вместо `DELETE` ([ADR 0020](../adr/0020-tombstone-instead-of-hard-delete.md), [0035](../adr/0035-reconcile-tombstone-via-journal.md)) |
| `get_asset` закрытого | возвращает карточку с `isCurrent=false`, `deletedAt`, `sources[].active=false`; `relations` пусты (закрытые связи отфильтрованы) |
| `search_assets`, `trace_dependencies`, footprint | только действующее; закрытое не попадает |
| Чего не делает | Не воскрешает и не удаляет физически; не отдаёт «когда и кто» удалил (это журнал и аудит); не возвращает закрытое через `search_assets` |

**Ограничение стенда.** Негативный случай `SNAPSHOT_MASS_CLOSE_LIMIT` на стенде воспроизводится ненадёжно: polling адаптера раз в 30 с подбирает tombstone'ы раньше snapshot. Его проверяет `ReconciliationIT.massCloseOverTheShareQuarantinesTheMarkerAndClosesNothing`; на показе только упомяните.

### Ожидаемый ответ (сокращённо)

```jsonc
// get_asset(legacy-statements) после
{"type":"Service","isCurrent":false,"deletedAt":"…",
 "properties":{"name":"legacy-statements","language":"cobol"},
 "relations":[],
 "sources":[{"source":"SCM","sourceType":"SERVICE","sourceId":"svc-legacy-statements","active":false,"authority":"MASTER"}]}
// search_assets {"query":"legacy-statements"} -> в items нет legacy-statements
// impact(vm-legacy-app-02): среди Service остался только sms-dispatcher
```

Эталоны: `expected/09-*.json`.

**Что подчеркнуть.** Граф — производная витрина: закрытие — следствие факта в мастере, а не ручное действие. История не теряется (узел и записи остаются), но не загрязняет ответы. Защита от ошибки источника: пустой или обрезанный snapshot не сотрёт каталог.

**Живой агент.** Связка с предыдущим: до скрипта попросить «Какие сервисы работают на vm-legacy-app-02?» (два), после — повторить (один). Вопрос про закрытый актив по `gid` агент задаст только если сохранил его в беседе. **Откат:** агент ответил из контекста — «повтори запрос tool»; затем скрипт 09.

**Типичные вопросы.**

- *Почему узел остаётся?* Для истории и аудита; `isCurrent=false`.
- *Что если источник временно вернул пустой список?* Сработает порог 50%, удаление не произойдёт.
- *Как вернуть?* Вернуть запись в источник: следующее событие/snapshot откроет узел заново.

---

## Сценарий 10. Администрирование: identity и карантин

**Скрипт:** `demo/10-admin.sh` · **Мутирует:** да (необратимо) · **Кейсы:** К-5а, К-5б, К-11

**Бизнес-вопрос.** «В CMDB один хост заведён дважды, а в репозиториях два похожих адреса. Кто решает, одно это или нет? Что делать с некорректной записью?»

**Что показываем.** Операторские действия (не через MCP): подтверждение кандидата identity, кандидат, остающийся открытым, и исправление записи из карантина. Затем админ-консоль.

**Шаги.**

1. Было: `vm-pay-01` и `cmdb-ci-88014` — два узла `ComputeInstance` и кандидат `score=0.60` (одинаковый `hostname` с точностью до регистра).
2. `POST /admin/identity/candidates/approve` (выживший — `vm-pay-01`): после проекции `cmdb-ci-88014` ссылается на `gid` выжившего; в `get_asset` одна карточка с двумя записями CMDB.
3. Кандидат К-5б `repo-payments-api ~ repo-payments-api-legacy` (`score=0.80`) остаётся `OPEN`; в графе два узла `Repository`. Автослияния нет.
4. К-11: развёртывание `dep-card-tokenizer-test-badcommit` в карантине (`INVALID_PAYLOAD`, commit не 40 hex); узла в графе нет.
5. Скрипт публикует исправленную запись (новую версию с корректным commit), событие обрабатывается, узел в графе.
6. `POST /admin/quarantine/release` старого события: оно повторно обрабатывает сохранённый невалидный payload и снова уходит в `QUARANTINED` с тем же кодом. Исправляет такое событие только новая версия в источнике, а не release.
7. Админ-консоль (только чтение): `http://127.0.0.1:8090`, пользователь `console-admin`, пароль `KEYCLOAK_CONSOLE_PASSWORD` из `.env`. Браузеру нужна запись `127.0.0.1 keycloak` в `hosts`. Показать: журнал и DLQ, кандидаты identity (К-5б открыт, К-5а `RESOLVED`), конфликты (К-4), общий `gid` у `vm-pay-01` и `cmdb-ci-88014`.

### Разбор

Admin REST ([03, раздел 4](../03-interfaces.md#4-admin-rest-ingestion-сервиса)) не входит в MCP и не публикуется наружу: скрипт вызывает его изнутри контейнера `ingestion` токеном со scope `architecture.admin` (секрет клиента идёт через stdin и временный файл контейнера, не через argv).

| Операция | Что делает | Чего не делает |
|---|---|---|
| `approve` | Оператор подтверждает пару `OPEN`-кандидата, выбирает выжившего; `identity_mapping`, `approved_crosswalk`, кандидат → `RESOLVED`, перепроекция записи на узел выжившего; узел-«дубль» закрывается с `mergedInto` | Не сливает историю прежнего `gid`; не перестраивает связи **других** записей сразу (это следующее событие или rebuild); не вызывается из MCP и консоли |
| Кандидат К-5б | Лежит `OPEN`, пока человек не решит | Автослияния нет ([ADR 0019](../adr/0019-identity-resolution-no-auto-merge.md)): совпадение адреса не доказывает одну сущность |
| `quarantine/release` | Возвращает событие в обработку (`RETRYING`) | Не исправляет payload: невалидная запись вернётся в карантин |
| Аудит | `admin_audit`: `STARTED` до выполнения, затем `SUCCEEDED`/`FAILED`; актор — `sub` токена; тексты исключений и payload не пишутся | |

В MCP **новых** tools для этого нет; агент увидит результат: `get_asset` по `vm-pay-01` покажет две записи CMDB.

### Ожидаемый ответ (сокращённо)

```jsonc
// POST /admin/identity/candidates/approve -> 200 {"status":"REPROJECTED"|"REMAPPED", "gid":"…", "previousGid":"…", …}
// get_asset(vm-pay-01) после
{"type":"ComputeInstance","isCurrent":true,
 "sources":[{"source":"CMDB","sourceId":"cmdb-ci-88014","active":true},{"source":"CMDB","sourceId":"vm-pay-01","active":true}],
 "relations":[{"relationType":"RUNS_ON","direction":"IN","name":"payments-api"},{"relationType":"RUNS_ON","direction":"IN","name":"payments-router"}]}
// кандидаты OPEN после: ровно "repo-payments-api ~ repo-payments-api-legacy score=0.80"
```

Эталоны: `expected/10-*.json`. Точный статус ответа `approve` скрипт не сверяет (HTTP 200).

**Что подчеркнуть.** Решение о слиянии принимает человек и оно записывается в аудит; система предлагает кандидатов, но не делает необратимых выводов. Некорректная запись не портит граф и не теряется: она в карантине с кодом и причиной.

**Живой агент.** Администрирование агенту недоступно (нет tools, scope `architecture.admin` у его токена не выдан). Проверка результата: «Покажи карточку хоста vm-pay-01: сколько записей источников?» → `search_assets` → `get_asset`. **Откат:** скрипт 10 и консоль.

**Типичные вопросы.**

- *Почему кнопки подтверждения нет в консоли?* Консоль только читает ([ADR 0026](../adr/0026-admin-console-readonly-loopback.md)); действие — admin REST.
- *Что с `cmdb-ci-88014` дальше?* Его записи отображаются на `gid` выжившего.
- *Откат слияния?* В демо не показывается; операция фиксируется в `admin_audit`.

---

## 4. Протокол изнутри: MCP Inspector

Раздел для разработчиков: что говорят клиент и сервер на уровне протокола. Inspector показывает тот же `POST /mcp`, что видит Claude Code.

**Запуск.**

```bash
MCP_INSPECTOR_VERSION=<проверенная версия> demo/inspector.sh
```

Версию пакета нужно задать явно (непиннованный пакет не запускается). Скрипт печатает `URL`, заголовок `Authorization: Bearer …` и срок жизни токена, затем запускает `npx @modelcontextprotocol/inspector` с `NODE_EXTRA_CA_CERTS` стенда. В UI Inspector: **Transport Type = Streamable HTTP**, URL — из вывода (`https://127.0.0.1:8443/mcp`), заголовок вводится в Authentication → Custom Headers. Токен живёт ~5 минут.

**Шаги показа.**

1. **`initialize`.** Connect в Inspector. Запрос:
   ```json
   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"inspector","version":"1"}}}
   ```
   Ответ: `serverInfo` — `arch-rag` `0.1.0`. Сервер работает в режиме `STATELESS`: `Mcp-Session-Id` не выдаётся, `tools/call` и `tools/list` принимаются без предварительного `initialize`. Транспорт — Streamable HTTP, ответы приходят как `application/json` (не `text/event-stream`). `GET /mcp` отвечает 405.
2. **`tools/list`.** Вкладка Tools → List Tools. Показать для каждого из 6 tools: описание, `inputSchema` (типы, обязательные поля, `required`), `annotations` (`readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=false`). Лимиты из [раздела 3](#3-как-читать-ответ-mcp) видны в описаниях параметров (`limit` 1..50, `maxDepth` 1..лимит сервера). Обратить внимание: в списке нет tool с Cypher и нет tool с записью.
3. **Сырой `tools/call`.** Tools → `search_assets` → `{"query":"EAM-1042"}`:
   ```json
   {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_assets","arguments":{"query":"EAM-1042"}}}
   ```
   В ответе `result.content[0].text` — **строка с JSON** (не вложенный объект), `result.isError=false`. Затем тот же вызов с `{"query":"payments","limit":51}`: `HTTP 200`, `result.isError=true`, текст `limit must be in 1..50` — ошибка tool возвращается как результат, а не как ошибка протокола.
4. **Отказ без scope/токена.** В Inspector очистить заголовок `Authorization` и повторить List Tools: `HTTP 401` с `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"`. Вызвать tool, которого нет в списке (`upsert_asset`; `curl` с заголовком `Authorization`, как в `07-access.sh`): `HTTP 403` — tool без записи в `tool-scopes` закрыт по умолчанию. Метаданные защищённого ресурса (RFC 9728) — `GET /.well-known/oauth-protected-resource` без токена.

   Токен «с токеном, но без нужного scope» на стенде не выпускается (см. [сценарий 7](#сценарий-7-безопасность-и-границы)); `insufficient_scope` показывают тесты mcp-server.

**Тот же шаг из командной строки** (если Inspector недоступен): `demo/07-access.sh` печатает коды 401/403/200 и `tools/list`; `demo/agent-setup.sh --check` выполняет `initialize` и `tools/list` по адресу агента.

**Что подчеркнуть.** Всё, что делает Claude Code, — обычные вызовы протокола; нет скрытого канала. Контракт проверяемый: `tools/list` сверяется с `expected/tools-list.json` в `demo-check.sh` и CI.

---

## Приложение. Соответствие скриптов, эталонов и кейсов

| № | Скрипт | Эталоны (`expected/`) | Кейсы | Инструменты |
|---|---|---|---|---|
| 1 | `01-search-card.sh` | `01-*` | К-10 | `search_assets`, `get_asset` |
| 2 | `02-runtime-footprint.sh` | `02-*` | К-2, К-3, К-8 | `find_runtime_footprint`, `get_asset` |
| 3 | `03-trace.sh` | `03-*` | К-1, К-4 | `trace_dependencies` (trace) |
| 4 | `04-impact.sh` | `04-*` | К-1, К-2 | `trace_dependencies` (impact) |
| 5 | `05-provenance-conflicts.sh` | `05-*` | К-4а, К-4б, К-9, К-10 | `explain_provenance`, `get_asset` |
| 6 | `06-freshness-unresolved.sh` | `06-*` | К-7, К-8 | `find_runtime_footprint`, `search_assets`, `get_asset` |
| 7 | `07-access.sh` | `07-*`, `tools-list.json` | — | HTTP 401/403, границы, `tools/list` |
| 8 | `08-redeploy.sh` | `08-*` | К-3 (история) | `find_runtime_footprint` |
| 9 | `09-lifecycle.sh` | `09-*` | К-6 | `get_asset`, `search_assets`, `trace_dependencies` |
| 10 | `10-admin.sh` | `10-*` | К-5а, К-5б, К-11 | admin REST, `get_asset`, консоль |

Эталоны нормализует `normalize.jq` (убирает `gid` и моменты времени, подставляет «Метка:имя», сортирует массивы); `demo-check.sh` сверяет ответы и печатает `diff -u` при расхождении. Если код или данные изменились намеренно, эталоны обновляет `demo-check.sh --update` (после сверки глазами с [dataset.md, раздел 5](dataset.md#5-кейсы--данные--сценарий)).
