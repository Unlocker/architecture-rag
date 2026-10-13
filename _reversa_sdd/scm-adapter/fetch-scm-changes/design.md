# fetch-scm-changes, дизайн

Модульный дизайн: [../design.md](../design.md). Общая логика: `adapters/adapter-core` (`HttpSourceConnector.java:33-147`, `Poller`, `WebhookHandler`; `_reversa_sdd/flowcharts/adapter-core.md`).

- Тест создаёт `StubSources.scm(Clock.systemUTC())`, `StubSourceServer`, `new HttpSourceConnector(ScmAdapter.SYSTEM, server.baseUri())` (`ScmAdapterContractTest.java:28-31`) 🟢; закрытие в `@AfterEach` (`:33-37`).
- Типы объектов SCM: `SERVICE`, `REPOSITORY` (`StubSources.java:20-21`) → normalizer `ScmMapper` (`ScmMapper.java:22-23`) 🟢; `svc-payments-api.systemCode = EAM-1042` ссылается на систему EAM, `repositoryId = repo-payments-api` (`StubSources.java:60-67`) 🟢.
- `snapshotComplete` — маркер, после которого допустимо удаление missing set (ловушка 9 CLAUDE.md); обработка — в `Reconciler`/`Poller`, не в этом модуле 🟡.
- Тесты не покрывают webhook-путь и подпись для SCM (в `adapter-core`) 🟡.
