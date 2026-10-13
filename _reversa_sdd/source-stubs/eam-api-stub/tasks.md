# eam-api-stub: задачи

## Pré-requisitos
- [ ] `Json.write`/`Json.parse` ([`../tasks.md`](../tasks.md) T-02); `StubSources.fields` для `typeObject` ([`../stub-source-journal/tasks.md`](../stub-source-journal/tasks.md) T-05)

## Tarefas

- [ ] T-01: `EamApiStore` — `upsert`/`archive`/`find`/`hasType`/`findIdByAttr`/`version`, `ver` по изменению `attrs`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:18-66, 95-96`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-02: `EamApiStore.page` — сортировка по `id|ctime|mtime` с `-`, хвост по `id`, страница за концом пуста
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStore.java:68-93`
  - Критерий готовности: AC-02, AC-08; 🔴 `sort=","` обрабатывается (Q-02)
  - Уверенность: 🟢 (поведение), 🔴 (граничные `sort`)
- [ ] T-03: `EamApiSeed.seeded` и константы
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:17-68`
  - Критерий готовности: AC-01; таблица [`../contracts.md`](../contracts.md) §4.3 совпадает побайтно
  - Уверенность: 🟢
- [ ] T-04: `EamApiTypeMeta` — заголовки и `options` для `fields`/`system`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiTypeMeta.java:13-62`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-05: `EamApiStub` — авторизация, маршрутизация, `list`, `types`, `archObject`, ошибки
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:36-240`
  - Критерий готовности: AC-01, AC-03, AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-06: `EamApiFormat` — разбор и проверка `ArchObject`, `isLastPage`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiFormat.java:13-83`
  - Критерий готовности: AC-07
  - Уверенность: 🟢
- [ ] T-07: 🔴 расширить заглушку под `docs/eam-api.yaml` (`kind=groups|computed`, `search*`, `enrich`, `only_names`) либо записать явный список «не поддерживается»
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:43, 122-131, 148-153`; `docs/eam-api.yaml:15-58, 18075-18079`; [`../questions.md`](../questions.md) Q-03
  - Критерий готовности: решение владельца/архитектора зафиксировано, тест на каждый неподдержанный параметр
  - Уверенность: 🔴

## Tarefas de Teste

- [ ] TT-01: `EamApiStubTest` — 18 тестов (`listReturnsBareArray…`, `seedSystemsCarryEamId…`, `paginationEnds…`, `sortByMtimeDescending…`, `fieldsParameter…`, `pageSizeIsClamped…`, `verGrows…`, `archivedObject…`, `retrieveById…`, `typeMetadata…`, `systemReferences…`, `requestsWithoutValidToken…`, `mutatingMethods…`, `blankToken…`, `parserRejects…`, `duplicateFields…`, `sortByUnsupportedKey…`, `truncatedJson…`)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/EamApiStubTest.java:52-203`
  - Критерий готовности: surefire зелёный без Docker
  - Уверенность: 🟢
- [ ] TT-02: 🟡 усилить `pageSizeIsClampedToMaximum` (создать > 500 объектов и проверить длину 500)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/EamApiStubTest.java:99-102`; Q-12
  - Критерий готовности: тест падает при снятии зажима
  - Уверенность: 🟡
- [ ] TT-03: 🔴 тесты на `sort=","`, `sort=id, -mtime`, `/api/types/{id}/` для несуществующего id, `itproducts` в `options`, тип без объектов
  - Origin: Q-02, пробелы Y1.7
  - Критерий готовности: каждый случай закреплён
  - Уверенность: 🔴

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06; T-07 после решения.

## Lacunas Pendentes (🔴)
Q-02, Q-03 — [`../questions.md`](../questions.md).
