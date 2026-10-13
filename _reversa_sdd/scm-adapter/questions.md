# Вопросы по adapters/scm-adapter

- Q-1 🔴 `ScmAdapter.create` не проверяет `config`, `journal`, `rawStore` на `null` (`adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/ScmAdapter.java:34-37`). Тест передаёт `null`-ы в ветке отказа (`ScmAdapterContractTest.java:49`) и полагается на то, что проверка источника срабатывает раньше. Нужна ли явная `Objects.requireNonNull`? Кто валидирует `journal`/`rawStore` (`WebhookHandler`/`Poller` не читались в этом модуле)?
- Q-2 🔴 Формат реального API SCM не зафиксирован: контракт `/changes` и `/objects` описан только для заглушки (ADR 0010, `docs/03-interfaces.md:536-538`). Нужна ли замена `HttpSourceConnector` собственным коннектором (как у `eam`) — вопрос к архитектору.
