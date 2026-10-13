# eam-api-stub: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Wire-формат: [`../contracts.md`](../contracts.md) §2, §4.3. Потребитель: [`../../eam-adapter/requirements.md`](../../eam-adapter/requirements.md). Контекст интеграции: `docs/06-eam-integration.md`, контракт источника: `docs/eam-api.yaml`.

## Visão Geral

`EamApiStub` — JDK-`HttpServer`, отдающий данные в формате реального EAM Tool API (`ArchObject {id, ver, datetime, attrs}`): списки типов `platforms`, `itproducts`, `itsystems`, объект по `id`, метаданные типов `/api/types/**`. Данные хранит `EamApiStore` (тип → `id` → объект), наполняет `EamApiSeed`. Заглушка нужна, чтобы EAM-адаптер читал источник, который выглядит как реальный (целочисленный `id` как ключ, `ver` как версия, голый массив без `total`, токен `Authorization: Token`), пока доступа к настоящему EAM нет (`docs/06-eam-integration.md:89`). Только чтение 🟢.

## Regras de Negócio

- Токен обязателен и не пуст; `Authorization` должен быть `Token <токен>`, сравнение `MessageDigest.isEqual`; иначе `401 {"detail":"Authentication credentials were not provided."}` для любых путей, включая неизвестные 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:54-59, 93-95, 186-191`).
- Метод ≠ `GET` → `405 {"detail":"Method not allowed"}` (после проверки токена) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:96-98`).
- Список `GET /api/<type>/`: ответ — голый JSON-массив `ArchObject`; `page` по умолчанию 1, `page_size` по умолчанию 50, максимум 500 (больше — зажимается без ошибки); `page < 1` или `page_size < 1` → `400`; нечисловые → `400 "<name> must be an integer"`; страница за концом — `[]`, не `404` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:122-131, 201-210`; `docs/06-eam-integration.md:96`).
- Сортировка `sort`: список ключей через запятую, `-` — убывание, допустимы `id`, `ctime`, `mtime`, при равенстве — по `id`; без `sort` — по `id`; иной ключ → `400 "sort supports only id, ctime, mtime"` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:68-93`).
- `fields=a,b` оставляет в `attrs` только перечисленные ключи; `id`, `ver`, `datetime` не фильтруются; повторы и неизвестные имена безвредны; пробелы не обрезаются 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:167-184`).
- Объект `GET /api/<type>/{id}/`: нечисловой `id` → `400 "id must be an integer"`; неизвестный `id` или тип → `404 {"detail":"Not found."}`; лишние сегменты → `404` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:109-119, 193-199`).
- `datetime` объекта = `mtime` (`Instant`, UTC); `ver` = 1 у нового объекта и растёт на 1 только при изменении `attrs`; `attrs` заменяются целиком; одинаковые `attrs` — no-op 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:27-37`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:178-183`).
- `archive(type, id)` физически убирает объект: он исчезает из списка и из `GET /{id}/` (→ `404`), повторный `upsert` начинается с `ver = 1` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:39-45`; `docs/06-eam-integration.md:94`).
- Метаданные: `GET /api/types/` — три `typeObject` по имени; `GET /api/types/{id|name}/` — один; `GET /api/types/{id|name}/options/?kind=fields|system` — `{"options":[…]}`; иной `kind` → `400`; неизвестный тип → `404` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:133-156`).
- Ссылки `product_id` и `platform_id` у ИТ-системы — одиночные числа; в метаданных `multiple:false` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:61-66`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:39-43`).
- `EamApiFormat.parseArchObjects` принимает только массив; элемент — объект с обязательными `id`, `ver`, `attrs`; `id` и `ver` — целые (`Long`), `attrs` — объект, `datetime` (если задан) — ISO-8601 `Instant` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java:28-61`).
- 🔴 Уже спеки: `kind ∈ {fields, system}`, нет `search*`/`enrich`/`only_names` — Q-03. 🔴 `sort=","` → NPE, `sort=id, -mtime` → 400 — Q-02.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Список объектов типа в формате `ArchObject[]` с пагинацией, сортировкой, `fields` | Must | AC-01, AC-02 |
| RF-02 | Объект по `id`, ошибки `400/404` | Must | AC-03 |
| RF-03 | `ver`/`mtime` по факту изменения, `archive` | Must | AC-04 |
| RF-04 | Метаданные типов `/api/types/**` | Should | AC-05 |
| RF-05 | Аутентификация токеном, только `GET` | Must | AC-06 |
| RF-06 | Общий разбор и проверка `ArchObject` | Should | AC-07 |
| RF-07 | Seed: 2 платформы, 2 решения, 2 системы, согласованные с SCM | Must | AC-01 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Segurança | Токен — байты в приватном поле; не попадает в ответы и исключения | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:46, 59, 186-191` | 🟢 |
| Segurança | Тексты `detail` фиксированы либо содержат имя параметра, значение ввода не эхуется | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:126, 197, 208` | 🟢 |
| Confiabilidade | `EamApiStore` потокобезопасен | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:28, 40, 48, 56, 60, 64, 69` | 🟢 |
| Compatibilidade | Лимиты и метаданные — допущения «не подтверждено» | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:34, 38-41`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:8-12` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: список и seed (EamApiStubTest.listReturnsBareArrayOfArchObjectsForAllThreeTypes, seedSystemsCarryEamIdMatchingScmSystemCode)
Дано EamApiSeed.seeded(clock) и токен
Когда GET /api/platforms/, /api/itproducts/, /api/itsystems/ с заголовком Authorization: Token <токен>
Тогда 200, каждое тело разбирается EamApiFormat.parseArchObjects и содержит 2 объекта
Когда GET /api/itsystems/
Тогда id = [5101, 5102], attrs.eam_id = [EAM-1042, EAM-2001]

# AC-02: страницы, сортировка, поля (EamApiStubTest.paginationEndsWithShortThenEmptyPage, sortByMtimeDescendingPutsLatestChangeFirst, fieldsParameterRestrictsAttrs)
Когда GET /api/platforms/?page=1&page_size=2 и ?page=2&page_size=2
Тогда первая страница из 2 объектов (isLastPage=false), вторая — []
Дано объект 3101 изменён позже остальных
Когда GET ?sort=-mtime
Тогда порядок [3101, 3102]; при ?sort=-id — [3102, 3101]
Когда GET /api/itsystems/?fields=name
Тогда attrs содержит только name

# AC-03: объект и ошибки (EamApiStubTest.retrieveByIdAndUnknownIdOrType)
Когда GET /api/itsystems/5101/ — 200; /api/itsystems/1/ — 404; /api/nosuchtype/ — 404; /api/itsystems/abc/ — 400

# AC-04: версия и архив (EamApiStubTest.verGrowsOnChangeAndStaysOnRepeat, archivedObjectDisappearsFromListAndRetrieve)
Дано upsert("platforms", 9, {name:X}) дважды
Тогда ver = 1
Когда upsert с {name:Y}
Тогда ver = 2
Дано archive("platforms", 3102)
Тогда список платформ состоит из 1 объекта, GET /api/platforms/3102/ — 404

# AC-05: метаданные (EamApiStubTest.typeMetadataIsServed, systemReferencesToSolutionAndPlatformAreSingleIds)
Когда GET /api/types/ — 3 объекта; /api/types/12/ — 200; /api/types/itsystems/options/?kind=fields — непустой options; /api/types/12/options/?kind=system — содержит eam_id
Тогда /api/types/12/options/?kind=groups — 400
Тогда product_id и platform_id системы — числа, а в options fields — multiple=false

# AC-06: аутентификация и методы (EamApiStubTest.requestsWithoutValidTokenAreRejected, mutatingMethodsAreNotAllowed, blankTokenIsRejected)
Когда запрос без заголовка, с Token wrong, с Bearer <токен>
Тогда 401
Когда POST, PATCH, DELETE с верным токеном
Тогда 405
Когда new EamApiStub(store, " ")
Тогда IllegalArgumentException

# AC-07: формат (EamApiStubTest.parserRejectsMalformedArchObjects, duplicateFieldsAreAccepted, truncatedJsonFailsWithIllegalArgument)
Когда parseArchObjects({"items":[]}), id как строка, нет ver, datetime = "x"
Тогда IllegalArgumentException в каждом случае
Когда GET ...?fields=name,name — 200, attrs содержит только name
Когда parse("[{\"id\":1") и parse("[")
Тогда IllegalArgumentException

# AC-08: неверная сортировка (EamApiStubTest.sortByUnsupportedKeyIsRejected)
Когда GET /api/platforms/?sort=name
Тогда 400

# AC-09 (отказ, не покрыто тестом) 🔴
Дано sort=","
Когда GET /api/platforms/?sort=,
Тогда ожидается 400 {"detail":...}, фактически NullPointerException и обрыв соединения (Q-02)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-02, RF-03, RF-05, RF-07 | Must | Единственный источник `ITSystem`/`Solution`/`Platform` в PoC (`docs/06-eam-integration.md:128`) |
| RF-04, RF-06 | Should | Метаданные и контрактные тесты fixtures |
| `search`, `enrich`, `kind=groups` и `kind=computed` | Won't | Вне допущений PoC (🔴 Q-03) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java` | `EamApiStub` | 🟢 |
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java`, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java`, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java`, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java` | хранилище, seed, метаданные, формат | 🟢 |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/EamApiStubTest.java` | 18 тестов | 🟢 |
