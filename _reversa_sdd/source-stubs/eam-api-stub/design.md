# eam-api-stub: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Контракт: [`../contracts.md`](../contracts.md) §2, §4.3.

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `EamApiStub(store, token)` | `(EamApiStore, String)` | loopback, порт 0 | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:49-52` 🟢 |
| `EamApiStub(store, token, address)` | | запускает сервер; `IllegalArgumentException("token must not be blank")`; `IllegalStateException("cannot start EAM API stub")` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:54-67` 🟢 |
| `baseUri()` / `port()` / `close()` | | `http://127.0.0.1:<port>` / порт / `stop(0)` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:69-75, 234-237` 🟢 |
| `EamApiStore.upsert` | `(String type, long id, Map attrs)` | создаёт (`ver 1`) или обновляет (`ver+1`) только при изменении `attrs` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:27-37` 🟢 |
| `EamApiStore.archive` | `(type, id)` | удаляет объект | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:39-45` 🟢 |
| `EamApiStore.findIdByAttr` / `version` | `(type, attr, value) -> Optional<Long>` / `(type, id) -> Optional<Long>` | служебные чтения для тестов | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:47-58` 🟢 |
| `EamApiSeed.seeded` | `(Clock) -> EamApiStore` | seed из 6 объектов | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:48-68` 🟢 |
| `EamApiSeed` константы | `PLATFORM_TYPE`, `IT_SYSTEM_TYPE`, `SOLUTION_TYPE`, id объектов, `OWNER_TEAM` | | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:19-44` 🟢 |
| `EamApiFormat.*` | см. модульный дизайн | | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java:17-82` 🟢 |

## Fluxo Principal

`route(exchange)` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:92-120`:

1. Авторизация (`Token <токен>`, постоянное время) — иначе 401.
2. Метод `GET` — иначе 405.
3. `path = rawPath` после `/api/`, сегменты по `/`; `query` — параметры, URL-декодированные.
4. Пустой первый сегмент → 404.
5. Первый сегмент `types` → `types(parts, q)`:
   - 1 сегмент: `typeObject` для каждого из `TYPE_IDS`, по алфавиту;
   - иначе тип ищется по числовому `id` либо по имени; нет → 404;
   - 2 сегмента → `typeObject`; 3 сегмента с `options` → проверка `kind ∈ {fields, system}` (иначе 400) и `{"options": EamApiTypeMeta.options(type, kind)}`; остальное → 404.
6. Тип отсутствует в `EamApiStore` (`hasType`) → 404. Заметьте: тип появляется в хранилище с первым `upsert`; без объектов он «неизвестен» (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:60-62`) 🟡.
7. 1 сегмент → `list(type, q)`; 2 сегмента → `store.find(type, parseId(parts[1]))`; больше → 404.

`list` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:122-131`: `page` (`intParam`, 1), `pageSize = min(intParam(page_size, 50), 500)`, `page < 1 || pageSize < 1` → `IllegalArgumentException`; `store.page(type, sort, page, pageSize)` → массив `archObject(entry, fields)`.

`EamApiStore.page` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:68-84`: копия значений типа; компаратор по умолчанию `id`; для `sort` — цепочка `thenComparing` по ключам, затем `thenComparingLong(id)`; `from = min((page-1)·pageSize, size)`; `subList(from, from + pageSize)`.

`handle` преобразует `IllegalArgumentException` из `route` в `400 {"detail": <сообщение>}` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:77-90`) 🟢.

## Fluxos Alternativos

- **`fields` пусто или пробелы** → фильтр не применяется 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:169`).
- **Незнакомый параметр списка** (`search`, `enrich`, `only_names`, `search_by`, `search_mode`) → молча игнорируется 🔴 (Q-03).
- **`sort` с пробелом после запятой перед `-`** → `400` 🔴 (Q-02, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:75-76`).
- **`sort=","`** → `NullPointerException` на `chain.thenComparingLong`, ответа нет 🔴 (Q-02, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:73-79`).
- **Архив + повторный `upsert`** → `ver = 1`, `ctime = mtime = now` 🟡 (Q-08).

## Dependências

`EamApiStore`, `EamApiSeed` (`TYPE_IDS`, константы), `EamApiTypeMeta` (метаданные), `Json`, `StubSources.fields` (для `attrs` типа) (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:163`) 🟢. Потребители: `adapters/eam-adapter` (`EamApiConnector`) — [`../../eam-adapter/requirements.md`](../../eam-adapter/requirements.md); тесты `platform/integration-tests` через `EamApiSupport` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EamApiSupport.java:17-38`) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Ключ объекта — целочисленный `id`; связь SCM → EAM идёт `systemCode → eam_id → id` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:9-16` | 🟢 |
| Ссылки между объектами — одиночные числовые `product_id`, `platform_id` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:55-66` | 🟢 |
| Список — голый массив, конец выборки — страница короче `page_size` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:26-28`; `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java:79-82`; `docs/06-eam-integration.md:96` | 🟢 |
| Тип «решение» — одна константа `SOLUTION_TYPE` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:23-27` | 🟢 |
| Единый разбор `ArchObject` для заглушки и контрактных тестов | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java:8-13` | 🟢 |

## Observabilidade

Нет. Диагностика — по `detail` в теле ошибки.

## Riscos e Lacunas

- 🔴 Q-02: `sort=","` и `sort=id, -mtime`.
- 🔴 Q-03: уже `docs/eam-api.yaml`.
- 🟡 Q-08: архив без истории.
- 🟡 Q-18: порядок элементов в сообщении `kind must be one of [...]`.
- 🟡 Тип без объектов отвечает `404`, как неизвестный (`hasType` проверяет наличие ключа в `byType`) — тест на это отсутствует.
