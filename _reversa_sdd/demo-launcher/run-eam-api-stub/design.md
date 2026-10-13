# run-eam-api-stub: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §2.3, §3.3. Блок-схема: [`../../flowcharts/demo-launcher.md`](../../flowcharts/demo-launcher.md) («Управление заглушкой EAM API `EamApiControl`»).

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `DemoLauncher.runEamApiStub` | `(LauncherConfig.EamApiStub) -> void throws InterruptedException` | блокируется до SIGTERM/SIGINT; `IOException` при старте control → `IllegalStateException` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:97-106 🟢 |
| `LauncherConfig.eamApiStub` | `(Map<String,String>) -> EamApiStub` | `IllegalArgumentException`, если нет токена | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:156-163 🟢 |
| `LauncherConfig.EamApiStub` | record(`int port, String token`) | `toString` с `token=***` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:89-95 🟢 |
| `EamApiControl(EamApiStore, InetSocketAddress)` | package-private, `AutoCloseable` | контексты `/control/upsert`, `/control/archive` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:31-36 🟢 |
| `EamApiControl.upsert / archive` | `(EamApiStore, Map<String,Object>) -> String` | JSON-тело ответа | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:42-57 🟢 |
| `EamApiControl.handle` | `(HttpExchange, Action) -> void` | 200/400/405 | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:77-98 🟢 |
| `EamApiControl.MAX_BODY_BYTES`, `TYPES` | `64 * 1024`, `Set.of("platforms","itproducts","itsystems")` | | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:25, 27 🟢 |

## Fluxo Principal

1. `store = EamApiSeed.seeded(Clock.systemUTC())` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:98) 🟢.
2. `try-with-resources`: `EamApiStub(store, token, 0.0.0.0:STUB_PORT)` и `EamApiControl(store, 0.0.0.0:8091)`; лог `EAM API stub listens on <port>, control on <port>`; `awaitShutdown()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:99-103) 🟢.
3. `IOException` при создании сервера/управления → `IllegalStateException("cannot start EAM API stub control")` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:104-106) 🟢.
4. Запрос к `/control/upsert` или `/control/archive` → `handle`: `POST` → `Json.parseObject(readBody)` (≤ 64 КиБ + 1) → действие (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:77-98, 100-106) 🟢.
5. `upsert`: `type` (в allowlist), `id` (`Long`), `attrs` (объект, ключи → `String`); `store.upsert(type, id, attrs)`; ответ `{"ver":N}` — `store.version(type, id).orElseThrow()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:42-52) 🟢.
6. `archive`: `store.archive(type, id)`; ответ `{}` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:54-57) 🟢.
7. `IllegalArgumentException` из любого шага → `400 {"error":"invalid request"}` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:88-91) 🟢.

## Fluxos Alternativos

- **Не `POST`** — `405 {"error":"POST expected"}` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:81-83).
- **`id` не целое** (строка, дробное) — `400`: проверка `instanceof Long` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:66-71; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:69).
- **Неизвестный `type`** — `400` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:59-64; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:68).
- **Другое исключение** (не `IllegalArgumentException`) — не перехватывается веткой 400; поведение определяется JDK `HttpServer` 🟡 (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:88-91).
- **Изменяющие методы на `/api/**`** — `405` в `EamApiStub` (вне модуля) (platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:76-80).

## Dependências

- `EamApiStore`, `EamApiSeed`, `EamApiStub` — `platform/source-stubs` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:26-31, 65`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:20-30`) 🟢.
- `Json.parseObject` — `adapter-core` 🟢.
- Потребитель: `adapter-eam` в режиме `eam-api` ([`../run-adapter/design.md`](../run-adapter/design.md)); формат API — `docs/eam-api.yaml`, описание — `docs/06-eam-integration.md:125-131` 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Управляющий вход по образцу `StubControl`, но с allowlist'ом `type` и числовым `id` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:15-22, 27, 66-71 | 🟢 |
| `upsert` заменяет `attrs` целиком; `ver` растёт только при изменении | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:42-52; platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/EamApiControlTest.java:53-55 | 🟢 |
| `archive` идемпотентен | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:54-57 | 🟢 |
| `/api/**` read-only: изменения источника — только через control | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:18-19 | 🟢 |
| Допущение: тип ИТ-решения `itproducts` не подтверждён (кандидаты `msys`, `okita`, `aknsd`) | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:23-27` | 🟡 |

## Estado Interno

`EamApiStore` — in-memory, общий для `EamApiStub` и `EamApiControl`; сбрасывается перезапуском контейнера 🟡.

## Observabilidade

Лог INFO `EAM API stub listens on <port>, control on <port>` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:101-102); отдельных логов запросов control нет 🟢.

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 20)] `TYPES` продублирован литералами и не связан с `EamApiSeed` (Q-03).
- 🟡 Управляющий вход без аутентификации (Q-02).
- 🟡 Тип ИТ-решения (`itproducts`) — допущение, вынесенное в `EamApiSeed.SOLUTION_TYPE` (`docs/06-eam-integration.md`, раздел «Открытые вопросы»).
