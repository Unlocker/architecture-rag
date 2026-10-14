# 0033. Единый источник общих значений

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
