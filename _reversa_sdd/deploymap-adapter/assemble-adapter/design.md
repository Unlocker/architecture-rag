# assemble-adapter, Design

Опирается на [../design.md](../design.md) (Interface, Fluxo Principal).

## Последовательность
1. Вызывающий получает `AdapterConfig` (через `config` или конструктор) 🟢.
2. `create`: `if (config.system() != SYSTEM) throw new IllegalArgumentException(...)` (`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:34-36`) 🟢.
3. `return new SourceAdapter(config, journal, rawStore)` (`:37`) → `new HttpSourceConnector(config.system(), config.sourceBaseUri())` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/SourceAdapter.java:29-31`) 🟢.
4. Стенд вызывает `startWebhook`, `startPolling`, `startReconciliation` (`DemoLauncher.java:124-126`) 🟢.

## Заметки
- Сравнение источника идёт по ссылке enum (`!=`) 🟢.
- Ошибка конфигурации — исключение, не код статуса; ничего не глушится 🟢.
- Расхождение с предыдущим анализом: `flowcharts/deploymap-adapter.md:11` пишет `new SourceAdapter(config, HttpSourceConnector, journal, rawStore)`; код вызывает 3-аргументный конструктор, `HttpSourceConnector` создаёт само `SourceAdapter` (`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:37`; `SourceAdapter.java:29-31`) 🟢. Блок-схема исправлена в R.6c (UNLOCKER-275).
