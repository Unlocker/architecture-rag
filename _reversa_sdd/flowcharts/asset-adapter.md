# Блок-схемы: adapters/asset-adapter

> Archaeologist, R.2b. Пути относительно `adapters/asset-adapter/src/main/java/`. Модуль — тонкая привязка (`AssetAdapter.java:15-39`); поток данных после сборки описан в [`adapter-core.md`](adapter-core.md).

## Сборка адаптера (`AssetAdapter.create`, `AssetAdapter.java:32-38`) 🟢

```mermaid
flowchart TD
  A["AssetAdapter.create(config, journal, rawStore)"] --> C{"config.system() == SYSTEM (SourceSystem.CMDB)?"}
  C -- нет --> X["IllegalArgumentException: config is for ..., expected ..."]
  C -- да --> SA["new SourceAdapter(config, HttpSourceConnector, journal, rawStore)"]
  SA --> W["WebhookHandler + Poller (adapter-core)"]
```

Код источника `cmdb` → `source` событий `urn:corp:cmdb` (`SourceSystem.java:5-9`, `EventMapper.java:47-49`). Собственной логики нет.
