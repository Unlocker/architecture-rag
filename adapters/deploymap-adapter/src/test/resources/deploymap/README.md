# Fixtures deploy map (`deploymap-poc-1`)

Записи `DEPLOYMENT` временного формата `platform/source-stubs/.../DeployMapFormat` (см. его javadoc). Файл —
массив `{sourceId, sourceVersion, payload}`. **Реального источника deploy map нет**, fixtures собраны по
формату заглушки; ключи аннотаций (`archrag.io/*`) — решение архитектора фичи UNLOCKER-248.

SCM и EAM берутся из seed заглушек (`StubSources.scm`, `EamApiSeed`): репозиторий `repo-payments-api`
(`https://git.example.org/pay/payments-api`) и сервис `svc-payments-api` с `systemCode = EAM-1042`. Аннотация
`archrag.io/eam-system-id` несёт **`id`** объекта EAM (`5101` = `EamApiSeed.SYSTEM_PAYMENTS_CORE`), а не `eam_id`.

| Каталог | Сценарий |
|---|---|
| `baseline/` | позитив: ssh-URL нормализуется в `url` репозитория SCM, система `5101` = система сервиса, commit — 40 hex |
| `legacy-poc-0/` | прежний формат без `chartMeta`; остаётся валидным до перехода |
| `unknown-repository/` | репозитория нет в SCM |
| `system-mismatch/` | репозиторий `payments-api`, система `5102` (Ledger) вместо `5101` |
| `invalid-commit/` | commit не 40 hex |
| `commit-change/` | `deployments-v1.json` → `deployments-v2.json`: тот же `sourceId`, новый commit, `sourceVersion` 1 → 2 |

## Риск для H.2

Chart несёт `id` системы (`5101`), а у сервиса SCM `systemCode` — это `eam_id` (`EAM-1042`). Сравнение системы
для `Conflict` идёт через индекс `eam_id → id` (ADR 0024); в H.1 гарантируется только согласованность seed.
