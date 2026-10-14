# 0041. Единый источник общих значений

- **Статус:** Принято
- **Дата:** 2026-10-14
- **Источник:** решение владельца 2026-10-14 по вопросу 9 SDD-ревью R.7 (UNLOCKER-289); UNLOCKER-291
- **Кто принял:** владелец

## Контекст

Типы событий, схемы `dataschema`, коды источников (`urn:corp:<код>`) и статусы обработки были записаны литералами в нескольких модулях: `EventMapper`, `EventProcessor`, `StoredEventReader`, `ReplayService`, `Normalizer`, `HousekeepingJob`, `IngestionMetrics`, `SyncSources`. Два `switch` по коду источника и дубли списков в консоли расходились при добавлении значения (например, `MANUAL`), а ошибка проявлялась только на стенде.

## Решение

- **Java-потребитель берёт значение из `contracts/*`.** Типы и схемы событий — `CanonicalEvent` (`event-schemas`), коды и URN источников — `SourceSystemCode` (`canonical-model`: `code()`, `urn()`, `fromUrn(String)`), статусы — `ProcessingStatus`. Локальные копии литералов в `src/main` не заводятся; списки допустимых значений строятся из `values()`.
- **Потребитель не на Java** (UI, shell, nginx, yml, SQL-миграции) читает значение из собственной копии, и эта копия защищена контрактным тестом, который сверяет её с константой из `contracts/*`.
- **Дубль без защиты не допускается.** Новое общее значение без теста согласованности в ревью отклоняется.
- Литералы в `src/test` не считаются дублями: это golden-проверка wire-формата, она должна падать при изменении значения в `contracts/*`.
- Wire-формат событий и URN не меняются.

## Последствия

- `fromUrn` возвращает `MANUAL` для `urn:corp:manual`; вызывающая сторона решает, допустим ли он. `Normalizer` и `EventProcessor` отвергают его как `UNKNOWN_SOURCE`, как и прежде.
- Консоль (`access/admin-console`) явно зависит от `canonical-model` и `event-schemas`; зависимости от `ingestion/*` по-прежнему нет.
- **Откат.** Revert PR; формат данных не затронут.
- **Затронутый код:** `contracts/*`, `adapters/adapter-core`, `ingestion/*`, `access/admin-console`, `access/mcp-server`.

## Совместимость `identity_feature` при смене `repositoryUrl`

`FeatureNormalizer.repositoryUrl` (признак `REPOSITORY_URL`) делегирует в `GitUrls.matchKey` (UNLOCKER-292). Парсер один, поэтому часть значений в `identity_feature` меняется. Две политики сравнения остаются по решению владельца: признак кандидата сравнивается без учёта регистра пути (`matchKey`), алиас для deploy map (`GitUrls.normalize`) — с учётом.

Совпадают с прежними: https/http, `.git`, завершающий `/`, смешанный регистр, `user@`, scp с `git@`, порт у http(s), пробелы по краям. Расходятся шесть классов (фиксирует `RepositoryUrlCompatibilityTest`):

| Вход | Было | Стало |
|---|---|---|
| `ssh://git@host:2222/a/b` (порт ssh) | `host:2222/a/b` | `host/a/b` |
| `https://host` (URL без пути) | `host` | признака нет |
| `host:a/b`, `host:2222/a/b` (scp без `user@`) | `host:a/b`, `host:2222/a/b` | `host/a/b`, `host/2222/a/b` |
| `host/a/b` (строка без схемы) | `host/a/b` | признака нет |
| `https://git_srv/a/b` (хост, который `java.net.URI` не разбирает) | `git_srv/a/b` | признака нет |
| `https://host/a/my repo` (пробел в пути) | `host/a/my repo` | признака нет |

**Пересчёт: replay, без Flyway-миграции.** `identity_feature` производная: `EventProcessor.recordCandidates` на каждый upsert заменяет признаки ключа целиком, tombstone вызывает `forget`. SQL не повторит разбор `GitUrls`, а простое удаление `REPOSITORY_URL` оставит кандидатов без признака до прихода новых событий. Журнал не чистится (`HousekeepingJob` трогает только `SyncRun` и `admin_audit`), поэтому replay покрывает все ключи.

- После выкладки выполнить `POST /admin/replay` по каждому источнику, который даёт записи `Repository` (по `FeatureExtractor`; сейчас как минимум `urn:corp:scm`), на всё окно журнала. Полный `POST /admin/rebuild?confirm=true` тоже подходит, но тяжелее.
- До replay кандидаты по `REPOSITORY_URL` между старыми и новыми значениями могут не находиться.
- Ранее найденные `identity_candidate` не удаляются и статус не меняют (ADR-0019).
- Нового кода пересчёта нет: используются существующие эндпоинты replay/rebuild.
