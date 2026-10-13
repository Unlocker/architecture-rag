# run-source-stub: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §2.2, §3.2. Блок-схемы: [`../../flowcharts/demo-launcher.md`](../../flowcharts/demo-launcher.md) («`POST /control/upsert` заглушки источника»).

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `DemoLauncher.runStub` | `(LauncherConfig.Stub) -> void throws IOException, InterruptedException` | блокируется до SIGTERM/SIGINT | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:85-95 🟢 |
| `LauncherConfig.stub` | `(SourceSystem, Map<String,String>) -> Stub` | `IllegalArgumentException` при `WEBHOOK_TARGET` без `WEBHOOK_SECRET` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:142-153 🟢 |
| `LauncherConfig.Stub` | record(`system, port, webhookTarget, webhookSecret`) | `toString` с `secret=***` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:80-86 🟢 |
| `StubControl(StubSource, InetSocketAddress[, StubWebhookSender])` | package-private, `AutoCloseable` | поднимает `HttpServer` с контекстом `/control/upsert` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:37-46 🟢 |
| `StubControl.handleUpsert` | `(StubSource, StubWebhookSender, HttpExchange) -> void` | ответы 200/400/405 | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:52-87 🟢 |
| `StubControl.notifyAdapter` | `(StubSource, StubWebhookSender, String type, String id) -> int` | HTTP-статус адаптера либо `0` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:89-104 🟢 |
| `StubControl.MAX_BODY_BYTES` | `64 * 1024` | | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:31 🟢 |

## Fluxo Principal

1. `source = StubSources.seeded(Clock.systemUTC()).get(config.system())` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:86) 🟢.
2. `webhooks = config.webhookTarget() == null ? null : new StubWebhookSender(target, secret, clock)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:87-88) 🟢.
3. `try-with-resources`: `StubSourceServer(source, 0.0.0.0:STUB_PORT)` и `StubControl(source, 0.0.0.0:8091, webhooks)`; лог `stub <SRC> listens on <port>, control on <port>`; `awaitShutdown()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:89-94) 🟢.
4. Запрос `POST /control/upsert` → `handleUpsert`: метод `POST` → `Json.parseObject(readBody)` (≤ 64 КиБ + 1 байт читается и проверяется) → `type`, `id` непустые строки → `payload` объект → копия в `LinkedHashMap<String,Object>` → `notify = !Boolean.FALSE.equals(request.get("notify"))` → `source.upsert(type, id, payload).sourceVersion()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:56-70, 106-119) 🟢.
5. Если `webhooks != null && notify` → `notifyAdapter`: последнее событие `(type, id)` из `source.webhookEvents().reversed()`; нет → `0`; иначе `webhooks.send(event)` → HTTP-статус; `IllegalStateException` → WARNING и `0` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:72-75, 89-104) 🟢.
6. Ответ `200` и JSON `{"sourceVersion":N[,"webhookStatus":S]}` с `Content-Type: application/json` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:76, 82-85) 🟢.
7. `IllegalArgumentException` на любом шаге разбора → `400 {"error":"invalid request"}`, сообщение не включается (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:77-80) 🟢.

## Fluxos Alternativos

- **Не `POST`** — `405 {"error":"POST expected"}` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:56-58).
- **Другой путь** — `HttpServer` JDK возвращает `404` для необработанных контекстов; другие пути не регистрируются (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:44) 🟡.
- **Другое исключение** (например, `RuntimeException` из `source.upsert`) — не перехватывается `IllegalArgumentException`-веткой, обработчик завершается исключением JDK `HttpServer` (соединение закрывается без ответа) 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:77-80).
- **Закрытие** — `server.stop(0)` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:121-124) 🟢.

## Dependências

- `StubSource`, `StubSources`, `StubSourceServer`, `StubWebhookSender` — `platform/source-stubs` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:53-54`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:26`) 🟢.
- `Json.parseObject` — `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/Json.java` (лимит глубины разбора) 🟢.
- `WebhookEvent`, `WebhookSignature` — `contracts/source-spi` 🟢.
- Получатель webhook — `WebhookServer` адаптера (`POST /webhook`) ([`../run-adapter/design.md`](../run-adapter/design.md)).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Управляющий вход на отдельном порту, чтобы он не попал в публикуемый источник | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:16-21; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:86-91 | 🟢 |
| Сбой webhook не откатывает upsert (догонит polling) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:23-27 | 🟢 |
| Отправляется последнее событие `(type, id)`, а не конкретное | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:91-95 | 🟢 |
| `notify:false` позволяет проверить путь polling без webhook | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:23-25; `docs/04-poc-measurements.md:39` | 🟢 |

## Estado Interno

`StubSource` — in-memory, потокобезопасность определяется `source-stubs`; `StubControl` состояния не имеет (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:35) 🟡.

## Observabilidade

Лог INFO `webhook <eventId> -> adapter status <код>`; WARNING `webhook <eventId> not delivered: <message>` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:98, 101) 🟢. Сообщение исключения `StubWebhookSender` может содержать детали доставки (URL) 🟡 (Q-04).

## Riscos e Lacunas

- 🟡 Управляющий вход без аутентификации (Q-02); `type` без allowlist (Q-15).
- 🟡 Синхронная доставка webhook блокирует `HttpServer` (Q-16).
- 🟡 `StubWebhookSender` не закрывается (Q-07).
- 🔴 `docs/03-interfaces.md:534` утверждает отсутствие `/control/*` у заглушек (Q-12).
