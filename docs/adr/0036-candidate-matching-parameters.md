# 0036. Параметры candidate matching: веса признаков зафиксированы в коде

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** UNLOCKER-278; кандидат К-6 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269); согласие владельца в UNLOCKER-260 (2026-10-13); реализация — UNLOCKER-174; доработки — UNLOCKER-279, UNLOCKER-282
- **Кто принял:** владелец, 2026-10-13

## Контекст

ADR [0019](0019-identity-resolution-no-auto-merge.md) запрещает автоматическое объединение, но веса и пределы определяют, какие кандидаты вообще увидит оператор. Эти параметры в реестре не были зафиксированы.

## Решение

Как в коде сейчас:

1. **Веса признаков зафиксированы в коде, не в конфигурации** (`ingestion/identity-resolution/.../FeatureKind.java:11`): `HOSTNAME 0.6`, `REPOSITORY_URL 0.8`, `NAME 0.4` — сильные признаки, создают кандидата; `OWNER 0.2` — слабый, кандидата не создаёт (`FeatureKind.java:15-18`).
2. **`score = min(1.0, сумма весов различных видов совпавших признаков)`**, шкала 2 (`FeatureKind.java:38-46`).
3. **Сравнение внутри семейства меток:** `ComputeInstance` и его подтипы — одно семейство (`FeatureExtractor.java:51-55`).
4. **`MAX_MATCHES = 50`** совпадений на признак; сверх предела кандидаты по этому признаку не создаются (`PostgresIdentityCandidates.java:33`, `:219-229`).
5. **Параллельные `record` сериализует advisory-lock** по `(семейство, вид, значение)` (`PostgresIdentityCandidates.java:24`, `:37`).
6. **Признаки хранятся нормализованными**, без сырых значений.

Реализовано в UNLOCKER-279 (п. 7) и UNLOCKER-282 (п. 8):

7. Переполнение `MAX_MATCHES` не проходит молча (UNLOCKER-279): кроме WARN с видом признака и пределом, без значения, вызывается `OverflowListener.overflow(kind)` (`PostgresIdentityCandidates.java:74-79`, `:226-229`), а счётчик `archrag.identity.candidates.overflow` с тегом `feature` растёт на каждое переполнение (`ingestion/ingestion-service/.../IngestionMetrics.java:40`, `:54`). Сами кандидаты по переполненному признаку по-прежнему не создаются.
8. Жизненный цикл кандидата (UNLOCKER-282): `POST /admin/identity/candidates/approve` переводит кандидата из `OPEN` в `RESOLVED` с `resolved_at`/`resolved_by` (`ingestion/identity-resolution/.../PostgresCandidateApprovals.java:45`, `:56`, миграция `V8__identity_candidate_resolution.sql`) и перепроецирует последнюю применённую версию записи из raw на узел выжившего (`ingestion/ingestion-service/.../CandidateApprovalService.java:61`, `:83`, `EventProcessor.java:265`; `AdminController.java:201`).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Веса и предел в коде | Предсказуемо, меньше конфигурации | Смена веса — релиз | Выбран |
| B. Конфигурируемые веса | Гибко | Расхождение настроек между стендами; PoC этого не требует | Отклонён: код прямо говорит «не конфигурация» |
| C. ML-подобие | Точнее | Вне PoC | Отклонён: ADR [0022](0022-out-of-poc-scope.md) |

## Последствия

- **Положительные:** оператор видит стабильный и объяснимый набор кандидатов; признаки без сырых значений безопаснее для хранения.
- **Отрицательные / ограничения:** при переполнении `MAX_MATCHES` кандидаты по признаку не создаются, о потере сообщают WARN и счётчик (п. 7).
- **Затронутый код:** `ingestion/identity-resolution` (`FeatureKind`, `FeatureExtractor`, `PostgresIdentityCandidates`).

## Связанные ADR

[0019](0019-identity-resolution-no-auto-merge.md) — запрет auto-merge. [0022](0022-out-of-poc-scope.md) — ML вне PoC.
