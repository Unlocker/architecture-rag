# asset-adapter: задачи реализации

## Пре-реквизиты
- [ ] Модули `adapter-core`, `source-spi`, `event-schemas` собираются; `source-stubs` доступен в test-scope.
- [ ] `SourceSystem.CMDB("cmdb")` существует (`contracts/source-spi/.../SourceSystem.java:7`).
- [ ] Модуль зарегистрирован в `adapters/pom.xml` и `dependencyManagement` корневого `pom.xml:116`.

## Задачи

- [ ] T-01 Объявить `AssetAdapter` как `public final class` с приватным конструктором и `SYSTEM = SourceSystem.CMDB`.
  - Origin: `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter/AssetAdapter.java:15-20`
  - Критерий готовности: `AssetAdapter.SYSTEM == CMDB`, экземпляр создать нельзя.
  - Уверенность: 🟢
- [ ] T-02 `config(URI, String)` → `AdapterConfig.of(SYSTEM, uri, secret)`.
  - Origin: `.../AssetAdapter.java:22-25`
  - Критерий готовности: `config(...).sourceUrn()` = `urn:corp:cmdb`; пустой секрет даёт `IllegalArgumentException` из `AdapterConfig`.
  - Уверенность: 🟢
- [ ] T-03 `create(config, journal, rawStore)`: при `config.system() != SYSTEM` бросить `IllegalArgumentException("config is for <X>, expected CMDB")`, иначе `new SourceAdapter(config, journal, rawStore)`.
  - Origin: `.../AssetAdapter.java:27-38`
  - Критерий готовности: чужой `config` отвергается до обращения к `journal`/`rawStore`.
  - Уверенность: 🟢
- [ ] T-04 Класс-маркер `AssetAdapterModule` (final, приватный конструктор) и `package-info.java`.
  - Origin: `.../AssetAdapterModule.java:3-7`; `.../package-info.java:1-2`
  - Критерий готовности: класс в пакете `io.github.unlocker.archrag.assetadapter`.
  - Уверенность: 🟢
- [ ] T-05 `pom.xml`: зависимости `adapter-core`, `source-spi`, `event-schemas`; `source-stubs` со scope `test`; версий нет (BOM).
  - Origin: `adapters/asset-adapter/pom.xml:17-35`
  - Критерий готовности: `./mvnw -pl adapters/asset-adapter -am verify` собирается.
  - Уверенность: 🟢

## Тесты
- [ ] TT-01 Привязка к источнику, `urn:corp:cmdb`, отказ на чужом `config` (`AssetAdapterContractTest.adapterIsBoundToItsSource`).
- [ ] TT-02 Контрактные сценарии коннектора — см. [`source-contract/tasks.md`](source-contract/tasks.md).
- [ ] TT-03 Маркер на classpath (`AssetAdapterSmokeTest.moduleMarkerIsOnClasspath`).
- [ ] TT-04 (нет в коде 🟡) юнит на `create` с источником `CMDB`: возвращает `SourceAdapter` с `connector().system() == CMDB`.

## Порядок
1. T-05, T-01, T-04 (скелет); 2. T-02, T-03; 3. тесты TT-01…TT-03. Зависимостей от других задач нет.

## Открытые 🔴
См. [`questions.md`](questions.md).
