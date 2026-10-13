# Use case: create-adapter (сборка адаптера SCM)

Родитель: [../requirements.md](../requirements.md), дизайн — [../design.md](../design.md).

## Сценарий
Оркестратор (`DemoLauncher`, IT) строит конфигурацию `ScmAdapter.config(baseUri, secret)` и собирает `ScmAdapter.create(config, journal, rawStore)` (`platform/demo-launcher/.../DemoLauncher.java:141`; `ScmAdapter.java:23-38`) 🟢.

## Правила
- Только `config.system() == SCM` (`ScmAdapter.java:34`) 🟢.
- Дефолты `config`: replayWindow 5 мин, pageSize 100, pollInterval 30 с, reconcileInterval 6 ч, retry 5/500 мс/30 с (`AdapterConfig.java:32,57,60-63`, `RetryPolicy.java:31-33`) 🟢.
- Пустой/null `webhookSecret` → `IllegalArgumentException("webhookSecret is required")` (`AdapterConfig.java:39-41`) 🟢.
- Тест на пустой секрет в `scm-adapter` отсутствует 🟡.

## Критерии приёмки
```gherkin
Дано baseUri и секрет "secret"
Когда ScmAdapter.config(baseUri, "secret").sourceUrn()
Тогда "urn:corp:scm"   (ScmAdapterContractTest.adapterIsBoundToItsSource)

Дано AdapterConfig.of(EAM, baseUri, "secret")
Когда ScmAdapter.create(foreign, null, null)
Тогда IllegalArgumentException   (ScmAdapterContractTest.adapterIsBoundToItsSource)

Дано пустой секрет
Когда ScmAdapter.config(baseUri, " ")
Тогда IllegalArgumentException   (поведение AdapterConfig; теста в модуле нет)
```
