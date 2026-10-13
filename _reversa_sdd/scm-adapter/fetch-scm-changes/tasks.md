# fetch-scm-changes, задачи

- [ ] T-01 Использовать стандартный `HttpSourceConnector` без собственного кода — Origin: `ScmAdapter.java:37`, `SourceAdapter.java:29-31`; готово: TT-01..TT-05; 🟢
- [ ] TT-01 `snapshotWalksAllObjectsInPagesAndCompletes`
- [ ] TT-02 `incrementalPollingReturnsOnlyChangesAfterCursor`
- [ ] TT-03 `fetchByIdReturnsStateAndEmptyForUnknownObject`
- [ ] TT-04 `deleteIsVisibleAsTombstone`
- [ ] TT-05 `rateLimitCarriesRetryAfterAndLeavesStateIntact`
- [ ] TT-06 (нет в модуле) `limit <= 0` — 🟡

Порядок: T-01 → TT-01..05. Открытые 🔴: Q-2 ([../questions.md](../questions.md)).
