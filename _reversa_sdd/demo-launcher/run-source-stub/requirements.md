# run-source-stub: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §2.2, §3.2.

## Visão Geral

Запуск `DemoLauncher stub <EAM|SCM|CMDB|DEPLOY_MAP>`: процесс поднимает in-memory заглушку мастер-системы с начальными данными (`StubSources.seeded`) и HTTP-фасадом источника (`GET /changes`, `GET /objects/{type}/{id}`) на `ARCHRAG_STUB_PORT`, а также управляющий вход `POST /control/upsert` на порту 8091. Управляющий вход вносит изменение в источник и, если задан `ARCHRAG_WEBHOOK_TARGET`, шлёт адаптеру подписанный webhook. Через него сценарии стенда (backup/restore, измерения, проверка коммита развёртывания) меняют «источник».

## Regras de Negócio

- Из `StubSources.seeded(Clock.systemUTC())` берётся источник заданной системы (создаются все четыре, используется один) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:85-86).
- `StubWebhookSender` создаётся только при заданном `ARCHRAG_WEBHOOK_TARGET`; при этом обязателен `ARCHRAG_WEBHOOK_SECRET`; без цели секрет игнорируется 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:87-88; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:142-153; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/LauncherConfigTest.java:106-126).
- Источник слушает `ARCHRAG_STUB_PORT` (8080 по умолчанию) на всех интерфейсах; управляющий вход — 8091 (константа), на всех интерфейсах, не публикуется 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54, 89-90).
- Управляющий вход не обслуживается на порту источника (`/control/upsert` там `404`) 🟢 (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:86-91).
- `POST /control/upsert`: тело `{"type","id","payload","notify"?}`; `type` и `id` — непустые строки, `payload` — JSON-объект; иначе `400 {"error":"invalid request"}` без эха ввода и без изменения источника 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:60-66, 77-80, 106-111; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:63-71).
- Не-`POST` → `405 {"error":"POST expected"}` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:56-58; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:79-84).
- Тело ограничено 64 КиБ; больше → `400` (через `IllegalArgumentException("body too large")`) 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:31, 113-119; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:73-77).
- Успешный upsert всегда `COMPLETE`-запись; ответ `200 {"sourceVersion":N}` 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:70-76).
- Webhook шлётся, если сконфигурирован отправитель и `notify` не равно `false` (отключает только литерал `false`); отправляется последнее событие `(type, id)` из `source.webhookEvents()`; поле `webhookStatus` — HTTP-статус адаптера либо `0` при сбое доставки или отсутствии события 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:69-76, 89-104).
- Сбой доставки webhook не превращает upsert в ошибку: изменение уже в источнике, его догонит polling адаптера 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:23-27; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:155-178).
- Поле `webhookStatus` отсутствует, если webhook не отправлялся 🟢 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:72-76; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:140-153, 180-183).
- Подпись webhook — по `WebhookSignature` (HMAC секретом, заголовки `eventId`, timestamp, signature) 🟢 (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:93-138).
- Данные заглушки in-memory: перезапуск контейнера сбрасывает изменения к seed 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:86).
- Управляющий вход не аутентифицируется; `type` не ограничен allowlist'ом (в отличие от `EamApiControl`) 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:62; Q-02, Q-15).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Запуск заглушки источника из seed с HTTP-фасадом и управляющим входом | Must | AC-01 |
| RF-02 | `POST /control/upsert` вносит изменение и возвращает `sourceVersion` | Must | AC-02 |
| RF-03 | Валидация и лимит тела, метод, без эха ввода | Must | AC-03, AC-04 |
| RF-04 | Подписанный webhook адаптеру после upsert, статус в ответе | Must | AC-05 |
| RF-05 | `notify:false` и недоступный получатель не ломают upsert | Should | AC-06 |
| RF-06 | Управляющий вход не обслуживается на порту источника | Must | AC-07 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Тело — недоверенный ввод: лимит 64 КиБ, ошибки без эха | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:20-21, 31, 77-80 | 🟢 |
| Безопасность | Секрет подписи не печатается (`secret=***`) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:82-84 | 🟢 |
| Безопасность | Управляющий вход открыт на `0.0.0.0:8091` без аутентификации | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:90 | 🟡 |
| Производительность | Webhook отправляется синхронно внутри обработчика (до 10 с таймаута отправителя); `HttpServer` обрабатывает запросы последовательно | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:73-75; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:26, 74` | 🟡 |
| Disponibilidade | Healthcheck compose `curl -fs 'http://127.0.0.1:8080/changes?limit=1'` | platform/docker/docker-compose.yml:390-395 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (LauncherConfigTest.stubNeedsNoSecretsAndHasDefaultPort; StubControlTest.controlIsNotServedOnSourcePort — см. AC-07)
Дано LauncherConfig.stub(CMDB, {}) без переменных
Тогда порт 8080, webhookTarget = null; с ARCHRAG_STUB_PORT=8181 порт 8181
Дано запущен stub EAM
Когда GET /changes?limit=1 на порту источника
Тогда 200 (healthcheck compose)

# AC-02 (StubControlTest.upsertAppearsInChanges)
Дано валидное тело {"type":"IT_SYSTEM","id":"EAM-9","payload":{"name":"Backup Probe"}}
Когда POST /control/upsert
Тогда 200 с полем sourceVersion; GET /changes содержит EAM-9 и Backup Probe

# AC-03 (StubControlTest.invalidBodyIsRejectedWithoutChangingSource; getIsNotAllowed)
Дано тело "not json", "{}", id пустой строкой или payload массивом
Когда POST /control/upsert
Тогда 400 {"error":"invalid request"}, список изменений источника не изменился
Дано GET /control/upsert
Тогда 405

# AC-04 (StubControlTest.oversizedBodyIsRejected)
Дано тело больше 64 КиБ
Когда POST /control/upsert
Тогда 400

# AC-05 (StubControlTest.withTargetUpsertSendsSignedWebhook)
Дано заглушка с ARCHRAG_WEBHOOK_TARGET и секретом
Когда POST /control/upsert валидного объекта
Тогда 200 {"sourceVersion":N,"webhookStatus":202}, получатель получил один webhook с eventId последнего события, подпись равна WebhookSignature.sign(secret, timestamp, body)

# AC-06 (StubControlTest.notifyFalseSkipsWebhook; unreachableReceiverStillSucceedsWithFailedStatus; receiverErrorStatusIsReportedNotThrown; withoutTargetResponseHasNoWebhookStatus)
Дано "notify":false
Тогда webhook не отправлен, webhookStatus в ответе нет, изменение в источнике есть
Дано получатель недоступен
Тогда 200 с webhookStatus = 0 и sourceVersion
Дано получатель ответил 401
Тогда 200 с webhookStatus = 401 (статус отражён, не брошен)

# AC-07 (StubControlTest.controlIsNotServedOnSourcePort)
Дано POST /control/upsert на порту источника (ARCHRAG_STUB_PORT)
Тогда 404
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04, RF-06 | Must | Сценарии стенда меняют источник только через control |
| RF-05 | Should | Устойчивость сценариев к недоступному адаптеру |
| Аутентификация control | Won't | Сетевая граница (🟡 Q-02) |
| Персистентность данных заглушки | Won't | In-memory |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java | `StubControl` | 🟢 |
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java | `runStub` | 🟢 |
| platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java | `Stub`, `stub` | 🟢 |
