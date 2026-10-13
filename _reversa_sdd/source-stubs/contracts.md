# platform/source-stubs: внешние контракты

> Wire-формат двух HTTP-слоёв, исходящего webhook, курсоры, seed и формат deploy map. Реализация по этому документу должна быть совместима с адаптерами на уровне байтов JSON. Секреты не приводятся, только имена. Открытые вопросы — [`questions.md`](questions.md).

## 1. `StubSourceServer`: `GET /changes`, `GET /objects/{type}/{id}`

Общее 🟢: слушает `InetSocketAddress` (по умолчанию loopback и порт 0), JDK `HttpServer`, контексты `/changes` и `/objects/` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:41-56`). Любой метод, кроме `GET`, → `405` с телом `{}` и ещё до проверки расписания сбоев (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:107-108`). Все ответы: `Content-Type: application/json`, тело в UTF-8, длина задана заранее (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:121-125`). Аутентификации нет (`docs/03-interfaces.md:560`).

| Метод и путь | Вход | Успех | Ошибки | Трассировка |
|--------------|------|-------|--------|-------------|
| `GET /changes?cursor=<opaque>&limit=<n>` | `cursor` пустой или отсутствует → начало snapshot (`null`); `limit` по умолчанию `100`; параметры URL-декодируются в UTF-8; пары без `=` или с `=` в позиции 0 игнорируются | `200` + `ChangePage` JSON (см. §1.1) | `400 {}` при `limit` не число (`NumberFormatException` ⊂ `IllegalArgumentException`), `limit ≤ 0`, префикс курсора не `i`/`s`; сбой источника → статус сбоя; `405 {}` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:79-88, 159-171` |
| `GET /objects/{type}/{id}` | ровно два сегмента после `/objects/`, оба URL-декодируются | `200` + описание изменения (§1.1, объект `change`) | `404 {}` при ином числе сегментов или неизвестном объекте; сбой источника → статус сбоя; `405 {}` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:90-100` |

Сбои 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:113-117, 129-134`):

| Причина | HTTP-статус | Заголовок |
|---------|-------------|-----------|
| `StubSource.failNext(times, status, retryAfter)` либо `StubSourceServer.failOnCall(n, status, retryAfter)` | `status`, а `0` → `504` | `Retry-After: <целые секунды>` только если `retryAfter != null` |
| `IllegalArgumentException` из разбора/источника | `400` | нет |
| иные `RuntimeException` (курсор `s5` без `:` → `ArrayIndexOutOfBoundsException`) | ответа нет, обработчик `HttpServer` обрывает обмен | — 🔴 Q-01 |

Нумерация вызовов `failOnCall`: с 1, общая для обеих ручек, считаются только `GET` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:109-111, 129-134`).

### 1.1 Тело `ChangePage` и `change`

Порядок ключей фиксирован (`LinkedHashMap`) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:136-157`):

```json
{"changes":[{"sourceType":"IT_SYSTEM","sourceId":"EAM-1042","sourceVersion":1,"operation":"UPSERT","completeness":"COMPLETE","updatedAt":"2026-10-04T12:00:00Z","payload":{"name":"Payments Core","ownerTeam":"TEAM-PAY"}}],"nextCursor":"i4","hasMore":false,"snapshotComplete":true}
```

| Поле | Тип | Значения |
|------|-----|----------|
| `changes[].sourceType`, `sourceId` | string | как записано в журнале |
| `changes[].sourceVersion` | число (long) | монотонна на ключ, с 1 |
| `changes[].operation` | string | `UPSERT` или `DELETE` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/ChangeOperation.java:4-7`) |
| `changes[].completeness` | string | `COMPLETE` или `PARTIAL` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/Completeness.java:7-10`) |
| `changes[].updatedAt` | string | `Instant.toString()`, UTC (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:209`) |
| `changes[].payload` | object | поля объекта; `null` допустимы; для `DELETE` — `{}` |
| `nextCursor` | string | непрозрачный курсор; на последней странице продолжает инкрементальный polling |
| `hasMore`, `snapshotComplete` | boolean | `snapshotComplete` истинен только при `hasMore = false` |

`GET /objects/...` возвращает один объект `change` (без обёртки страницы), как на `200` выше 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:97`).

### 1.2 Грамматика курсора (непрозрачна для клиента, фиксирована для заглушки) 🟢

| Курсор | Смысл | Страница |
|--------|-------|----------|
| отсутствует или пусто | начало snapshot: `bound = размер журнала`, `after = 0` | см. ниже |
| `s<after>:<bound>` | продолжение snapshot после записи с `seq = after` при зафиксированной границе | первые `limit` объектов `latest` с `seq > after` |
| `i<seq>` | инкрементальный polling: записи журнала с `seq > <seq>`, не более `limit`, в порядке `seq`, включая `DELETE` | `nextCursor = "i" + <seq последней записи страницы>` (при пустой странице — тот же `i<seq>`); `hasMore = размер журнала > последний seq` |
| иной префикс | — | `IllegalArgumentException("unknown cursor")` → `400 {}` |

Snapshot: `latest` = последние `UPSERT`-записи ключей `type/id` с `seq ≤ bound`, упорядоченные по `seq`; `nextCursor = more ? "s"+last+":"+bound : "i"+bound`; `hasMore = more`; `snapshotComplete = !more` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:144-167`). Следствие: объект, изменённый после `bound`, приходит инкрементом с курсором `i<bound>`; удалённый объект в snapshot не попадает.

Битые курсоры (не покрыты тестами) 🔴: `i<не число>` и `s<не число>:...` → `NumberFormatException` (⊂ `IllegalArgumentException` → `400`); `s5` без `:` → `ArrayIndexOutOfBoundsException` → обрыв (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:138-140`).

## 2. `EamApiStub`: `GET /api/**`

Общее 🟢: контекст `/api/`; `Content-Type: application/json`; ошибки `{"detail":"<текст>"}` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:65, 77-90, 212-214`). Порядок проверок в маршрутизаторе (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:92-120`): (1) `Authorization: Token <token>` — иначе `401 {"detail":"Authentication credentials were not provided."}`; (2) метод ≠ `GET` — `405 {"detail":"Method not allowed"}`; (3) пустой путь — `404 {"detail":"Not found."}`; (4) первый сегмент `types` — метаданные; (5) тип неизвестен хранилищу — `404`; (6) 1 сегмент — список; 2 — объект; больше — `404`. Схема `Bearer`, неверный токен, отсутствие заголовка → одинаково `401`.

| Метод и путь | Параметры | Успех | Ошибки | Трассировка |
|--------------|-----------|-------|--------|-------------|
| `GET /api/{type}/` (и без завершающего `/`) | `page` (по умолчанию 1), `page_size` (по умолчанию 50, максимум 500, больше — зажимается), `sort`, `fields` | `200` — **голый JSON-массив** `ArchObject[]`; страница за концом — `[]` | `400` `page and page_size must be positive`; `400` `<name> must be an integer` (`page`/`page_size` не число); `400` `sort supports only id, ctime, mtime`; `404` для неизвестного типа | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:122-131, 201-210` |
| `GET /api/{type}/{id}/` | `fields` | `200` — один `ArchObject` | `400` `id must be an integer`; `404` `Not found.` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:115-118, 193-199` |
| `GET /api/types/` | — | `200` — массив `typeObject`, отсортированный по имени типа (`itproducts`, `itsystems`, `platforms`) | — | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:133-138` |
| `GET /api/types/{id\|name}/` | число (`11`, `12`, `13`) или имя типа | `200` — `typeObject` | `404` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:139-147` |
| `GET /api/types/{id\|name}/options/?kind=fields\|system` | `kind` обязателен | `200 {"options":[...]}` | `400` `kind must be one of [fields, system]` (порядок элементов в тексте не гарантирован, это `Set.of`); `404` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:148-154` |

### 2.1 Форматы объектов

`ArchObject` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:167-184`): `{"id":<long>,"ver":<long>,"datetime":"<mtime Instant>","attrs":{...}}`. `attrs` — полный набор полей объекта либо отфильтрованный по `fields` (ключи через запятую, без обрезки пробелов; неизвестные имена игнорируются; порядок ключей сохраняется из хранилища). Фильтрация к `id`, `ver`, `datetime` не применяется.

`typeObject` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:158-165`): `{"id":<11|12|13>,"ver":1,"datetime":"2026-01-01T00:00:00Z","attrs":{"name":"<заголовок>","typeurl":"<имя типа>"}}`; заголовки: `platforms` → «ИТ-платформы», `itsystems` → «ИТ-системы», `itproducts` → «ИТ-решения» (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:17-23`).

Опции типов (`kind`) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:25-61`): `system` → три поля `eam_id`, `eam_name`, `eam_permalink` (все `type: string`, без `target`/`multiple`); `fields` → `name` (`string`), `status` (`select`), `description` (`text`) плюс по типу: `platforms` — `lifecycle` (`select`); `itsystems` — `criticality` (`select`), `product_id` (`reference` → `itproducts`, `multiple:false`), `platform_id` (`reference` → `platforms`, `multiple:false`); остальные (`itproducts`) — `lifecycle` (`select`), `platform_id` (`reference` → `platforms`, `multiple:false`). Ключи `target`/`multiple` есть только у `reference`. Состав ключей — допущение «не подтверждено» (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:8-12`).

### 2.2 Расхождения с `docs/eam-api.yaml` 🔴 (Q-03)

`docs/eam-api.yaml:18075-18079` допускает `kind ∈ {computed, fields, groups, system}`, заглушка — только `fields|system` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:43, 150`). Для списка спека (`docs/eam-api.yaml:15-58`) объявляет `enrich`, `fields`, `only_names`, `page`, `page_size`, `search`, `search_by`, `search_mode`, `sort`; заглушка читает `page`, `page_size`, `sort`, `fields`, остальное молча игнорирует (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:122-131`).

## 3. Исходящий webhook `StubWebhookSender` (клиент, не endpoint)

`POST <target>` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:71-80`):

| Заголовок | Значение |
|-----------|----------|
| `Content-Type` | `application/json` |
| `X-Archrag-Event-Id` | `eventId` события (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:31`) |
| `X-Archrag-Timestamp` | epoch-секунды (`timestamp.getEpochSecond()`; для `send` — `clock.instant()`; для `sendAt` — заданный) |
| `X-Archrag-Signature` | `sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:46-49`) |

Тело — JSON из семи ключей в порядке `eventId`, `source`, `sourceType`, `sourceId`, `sourceVersion`, `operation`, `occurredAt`; без payload объекта (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:58-69`) 🟢. `occurredAt` — `Instant.toString()`. Ответ: возвращается только HTTP-статус (тело отбрасывается); ожидаемые: `202` принят, `401` для `OUT_OF_WINDOW`/`BAD_SIGNATURE`/`MALFORMED` на стороне адаптера. Негативные варианты: `sendAt(event, старый timestamp)` — корректная подпись, метка времени вне окна; `sendWithBadSignature` — подпись секретом `secret + "-wrong"` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:44-56`).

Идентификатор события, который выдаёт `StubSource`: `"<код источника>-evt-<seq>"`, где `код` — `SourceSystem.code()` (`eam`, `scm`, `cmdb`, `deploymap`), `seq` — порядковый номер записи в журнале с 1 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:192-202`) 🟢.

## 4. Seed

### 4.1 `StubSources.seeded(clock)` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:28-105`)

Все пары `type / id → payload` в порядке записи (версия каждого — 1):

| Источник | Тип | id | payload |
|----------|-----|----|---------|
| EAM | `TEAM` | `TEAM-PAY` | `name = Payments Team` |
| EAM | `IT_SYSTEM` | `EAM-2001` | `name = Ledger`, `ownerTeam = TEAM-PAY` |
| EAM | `IT_SYSTEM` | `EAM-1042` | `name = Payments Core`, `ownerTeam = TEAM-PAY` |
| EAM | `SERVICE_DEPENDENCY` | `dep-payments-api-ledger-api` | `from = svc-payments-api`, `to = svc-ledger-api`, `kind = SYNC`, `protocol = HTTP`, `criticality = HIGH` |
| SCM | `REPOSITORY` | `repo-payments-api` | `url = https://git.example.org/pay/payments-api`, `defaultBranch = main` |
| SCM | `SERVICE` | `svc-payments-api` | `name = payments-api`, `systemCode = EAM-1042`, `repositoryId = repo-payments-api` |
| SCM | `SERVICE` | `svc-ledger-api` | `name = ledger-api`, `systemCode = EAM-2001` |
| CMDB | `COMPUTE_INSTANCE` | `vm-pay-01` | `hostname = vm-pay-01.prod.example.org`, `kind = VIRTUAL_MACHINE`, `state = RUNNING`, `environment = prod` |
| DEPLOY_MAP | `ENVIRONMENT` | `prod` | `format = deploymap-poc-0`, `name = Production` |
| DEPLOY_MAP | `DEPLOYMENT` | `dep-payments-api-prod` | payload формата `deploymap-poc-1`, §5 |

Согласованность ключей между источниками: `SERVICE.systemCode` (SCM) = `IT_SYSTEM`-id (EAM, legacy) и `eam_id` объектов `EamApiSeed`; `DEPLOYMENT.service` = id сервиса SCM; `DEPLOYMENT.hosts` = id `COMPUTE_INSTANCE` CMDB; `annotation eam-system-id` = `EamApiSeed.SYSTEM_PAYMENTS_CORE` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:10-15, 83-105`).

Константы seed-деплоя: `PAYMENTS_API_COMMIT = 9fceb02d0ae598e95dc970b74767f19372d61af8`, `PAYMENTS_API_SSH_URL = git@git.example.org:pay/payments-api.git`, `PAYMENTS_API_NEW_COMMIT = 1b4f0e9851971998e732078544c96b36c3d01ced` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:107-114`) 🟢.

### 4.2 Сценарные хелперы вне seed 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:116-211`)

| Хелпер | Что добавляет |
|--------|---------------|
| `addDeploymentWithUnknownRepository` | `DEPLOYMENT dep-unknown-repo-prod`: chart `unknown-repo`, версия `0.1.0`, URL репозитория `https://git.example.org/pay/unknown-repo.git`, система `5101` |
| `addDeploymentWithSystemMismatch` | `DEPLOYMENT dep-payments-api-mismatch`: репозиторий `payments-api`, система `5102` (Ledger) вместо `5101` |
| `addDeploymentWithInvalidCommit` | `DEPLOYMENT dep-payments-api-bad-commit` с commit `not-a-commit` |
| `addDeploymentRedeploy` | перезаписывает `dep-payments-api-prod`: версия `1.4.3`, commit `PAYMENTS_API_NEW_COMMIT` (версия записи +1) |
| `addServiceWithBrokenReference` | `SERVICE svc-orphan`, `systemCode = EAM-UNKNOWN` |

### 4.3 `EamApiSeed.seeded(clock)` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:39-68`)

| Тип | id | attrs |
|-----|----|-------|
| `platforms` | 3101 | `name = Kubernetes Platform`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Shared container runtime` |
| `platforms` | 3102 | `name = PostgreSQL Platform`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Managed relational database` |
| `itproducts` | 7201 | `name = Card Processing`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Card payments processing solution`, `platform_id = 3101` |
| `itproducts` | 7202 | `name = Ledger Engine`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Double-entry ledger solution`, `platform_id = 3102` |
| `itsystems` | 5101 | `name = Payments Core`, `eam_id = EAM-1042`, `status = ACTIVE`, `criticality = HIGH`, `description = Payments processing system`, `product_id = 7201`, `platform_id = 3101`, `ownerTeam = TEAM-PAY` |
| `itsystems` | 5102 | `name = Ledger`, `eam_id = EAM-2001`, `status = ACTIVE`, `criticality = HIGH`, `description = General ledger system`, `product_id = 7202`, `platform_id = 3102`, `ownerTeam = TEAM-PAY` |

`ctime = mtime = clock.instant()` на момент `seeded`; `ver = 1`. Типы «решения» (`itproducts`) и `ownerTeam` — допущения «не подтверждено» (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:23-37`; `docs/06-eam-integration.md:91, 131`).

## 5. Формат payload deploy map (временный)

`deploymap-poc-0` (`DeployMapFormat.FORMAT`) и `deploymap-poc-1` (`FORMAT_V1`) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:24-47`):

- `ENVIRONMENT`: `format`, `name` (seed: `format = deploymap-poc-0`, `name = Production`) (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:92-93`).
- `DEPLOYMENT` (v1): `format = deploymap-poc-1`, `name` (= chart), `service`, `environment = prod`, `chart`, `chartVersion`, `chartMeta {name, version, appVersion (= chartVersion), annotations {archrag.io/eam-system-id, archrag.io/git-repo-url, archrag.io/git-commit}}`, `hosts [..]` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:180-200`).
- Ключи аннотаций: `archrag.io/eam-system-id` (целое `id` системы EAM строкой), `archrag.io/git-repo-url`, `archrag.io/git-commit` (40 hex в нижнем регистре) (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:35-42`).
- Формат временный: владелец ещё не решил, где хранятся deploy map и charts; после решения он заменяется, а не расширяется (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:3-8`; `docs/03-interfaces.md:550`). Расхождение набора полей с документом — Q-04.

## 6. `Json` (package-private)

`write` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:178-233`): `null` → `null`; `Boolean`; `Number` → `toString`; `Map` → объект (ключ `String.valueOf`, порядок итерации); `Collection` → массив; прочее → строка `toString()` (`Instant` → ISO-8601 UTC, enum → имя). Экранируются `"`, `\`, `\n`, `\r`, `\t`, символы `< 0x20` → `\u00xx` (строчный hex); символы ≥ 0x20, включая не-ASCII, пишутся как есть. `parse` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:21-176`): `Map` (порядок вставки), `List`, `String`, `Long` (число из `-` и цифр) либо `Double` (иначе), `Boolean`, `null`; хвостовые символы → `IllegalArgumentException("trailing characters at <pos>")`. Сбои на границах 🟡: бесконечная вложенность → `StackOverflowError`; `\` или обрезанная `\u` в конце строки → `StringIndexOutOfBoundsException` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:143-153`); число `-` или `1-2` → `NumberFormatException` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/Json.java:164-175`).

## 7. Что модуль не публикует

`/control/upsert`, `/control/archive` и `/health` принадлежат `demo-launcher`, а не этому модулю (`docs/03-interfaces.md:534` описывает отсутствие `/control/*` у `StubSourceServer`; `docs/06-eam-integration.md:128` — управление объектами на отдельном порту стенда) 🟡. См. [`../flowcharts/demo-launcher.md`](../flowcharts/demo-launcher.md).
