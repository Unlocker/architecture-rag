# run-eam-api-stub: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §2.3, §3.3. Интеграция EAM: `docs/06-eam-integration.md`.

## Visão Geral

Запуск `DemoLauncher eam-api-stub`: процесс поднимает заглушку EAM в формате реального API (`EamApiStub`, начальные данные `EamApiSeed`) на `ARCHRAG_STUB_PORT` с проверкой токена `Authorization: Token ...`, а также управляющий вход `POST /control/upsert` и `POST /control/archive` на порту 8091. Заглушку читает `adapter-eam` (PLATFORM, SOLUTION, IT_SYSTEM по `id`) рядом со старой `stub-eam` (TEAM, SERVICE_DEPENDENCY). Через управляющий вход сценарии стенда (`measure.sh`, `backup-restore-check.sh`) создают, меняют и архивируют объекты; сам `/api/**` остаётся только для чтения.

## Regras de Negócио

- Токен обязателен: `ARCHRAG_EAM_API_TOKEN` не задан или пуст → `IllegalArgumentException` с именем переменной, код выхода 2; значение токена в `toString` не попадает 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:156-163, 89-95; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:95-104).
- Порт заглушки `ARCHRAG_STUB_PORT` (8080 по умолчанию); управляющий вход — 8091 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:162; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54, 97-100).
- Начальные данные — `EamApiSeed.seeded(Clock.systemUTC())`, in-memory 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:98).
- `POST /control/upsert`: тело `{"type","id","attrs"}`; `type` ∈ `{platforms, itproducts, itsystems}`; `id` — JSON-целое (`Long`); `attrs` — JSON-объект; создаёт либо обновляет объект, `attrs` заменяются целиком; ответ `200 {"ver":N}` (`ver` растёт при изменении) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:27, 42-52, 59-71; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:47-55).
- `POST /control/archive`: тело `{"type","id"}`; убирает объект из основного списка; идемпотентно (неизвестный объект тоже `200 {}`) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:54-57; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:57-62).
- Не `POST` → `405 {"error":"POST expected"}`; невалидный JSON, тело > 64 КиБ, `type` вне allowlist, `id` не целое (в том числе строка), `attrs` не объект или отсутствует → `400 {"error":"invalid request"}` без эха ввода 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:77-106; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:64-74).
- `/api/**` заглушки остаётся только для чтения (изменяющие методы → `405`); `Authorization: Token <token>` обязателен (`401` без него) 🟢 (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:76-80; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:31, 93-97`).
- Управляющий вход не аутентифицируется и не публикуется; вызов — из контейнера 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:15-22; `platform/docker/README.md:51`).
- Список допустимых `type` продублирован литералами и может разойтись с `EamApiSeed` 🔴 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:27; Q-03).
- Webhook у `eam-api-stub` нет: адаптер EAM узнаёт об изменениях только polling'ом 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:97-106; `docs/04-poc-measurements.md:10`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Запуск `EamApiStub` и `EamApiControl` из seed с токеном из env | Must | AC-01 |
| RF-02 | `POST /control/upsert` создаёт/обновляет объект, возвращает `ver` | Must | AC-02 |
| RF-03 | `POST /control/archive` убирает объект из списка | Must | AC-03 |
| RF-04 | Валидация метода, тела, `type`, `id`, `attrs` без эха ввода | Must | AC-04 |
| RF-05 | `/api/**` только для чтения | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Тело — недоверенный ввод: лимит 64 КиБ, ошибки без эха | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:19-21, 25, 100-106 | 🟢 |
| Безопасность | Токен хранится только в записи `EamApiStub` (`token=***` в `toString`); сравнение токена в заглушке — `MessageDigest.isEqual` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:91-93; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:187-190` | 🟢 |
| Безопасность | Управляющий вход открыт на `0.0.0.0:8091` без аутентификации | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:100 | 🟡 |
| Disponibilidade | Healthcheck compose: `GET /api/platforms/?page_size=1` с токеном из секрета | platform/docker/docker-compose.yml:440-445 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (LauncherConfigTest.eamApiStubRequiresTokenAndHidesItInToString)
Дано env без ARCHRAG_EAM_API_TOKEN
Когда LauncherConfig.eamApiStub(env)
Тогда IAE с ARCHRAG_EAM_API_TOKEN; процесс завершится кодом 2
Дано ARCHRAG_EAM_API_TOKEN=tok-secret и ARCHRAG_STUB_PORT=9100
Тогда port = 9100, token = tok-secret, toString токена не содержит

# AC-02 (EamApiControlTest.upsertAppearsInApiListAndBumpsVer)
Дано {"type":"itsystems","id":5999,"attrs":{"name":"Probe"}}
Когда POST /control/upsert
Тогда 200 {"ver":1}; GET /api/itsystems/ с токеном содержит Probe
Когда тот же объект обновлён другими attrs
Тогда 200 {"ver":2}

# AC-03 (EamApiControlTest.archiveRemovesObjectFromMainList)
Дано объект platforms/3102 из seed
Когда POST /control/archive {"type":"platforms","id":3102}
Тогда 200; GET /api/platforms/3102/ -> 404; GET /api/platforms/ не содержит PostgreSQL Platform

# AC-04 (EamApiControlTest.invalidRequestsAreRejectedWithoutEcho)
Дано тело "not json SECRET", либо type="buffers", либо id="x", либо attrs отсутствует, либо метод GET
Когда запрос к /control/upsert
Тогда 400 (для GET 405) и "SECRET" в ответе нет

# AC-05 (EamApiControlTest.apiStaysReadOnly)
Дано токен заглушки
Когда POST /api/itsystems/ или DELETE /api/itsystems/5101/
Тогда 405
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | `adapter-eam` читает эту заглушку; сценарии стенда меняют EAM через control |
| Согласованность `TYPES` с `EamApiSeed` | Should | Сейчас не гарантирована (🔴 Q-03) |
| Webhook от заглушки | Won't | У `eam-api` вебхуков нет |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java | `EamApiControl` | 🟢 |
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java | `runEamApiStub` | 🟢 |
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java | `EamApiStub`, `eamApiStub` | 🟢 |
