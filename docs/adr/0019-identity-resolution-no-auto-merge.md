# 0019. Identity resolution без автоматического объединения; deterministic mapping и crosswalk в E1 до projector

- **Статус:** Принято
- **Дата:** 2026-10-04
- **Источник:** [`00-vision.md`, «Identity resolution»](../00-vision.md#identity-resolution); владелец 2026-10-04 (вариант B); UNLOCKER-173; [PR #10](https://github.com/Unlocker/architecture-rag/pull/10); [`01-development-order.md`](../01-development-order.md)
- **Кто принял:** владелец

## Контекст

Ошибочный merge опаснее дубликата: он создаёт ложные пути зависимостей. Projector привязывает записи через `(source, sourceType, sourceId) → gid`, и rebuild воспроизводим только если соответствие хранится. Код E3 недоступен ветке E1 до вливания в `develop`.

## Решение

Политика трёх ступеней: deterministic mapping, approved crosswalk, candidate matching. Автоматического merge нет: кандидаты порождают только `IdentityCandidate`. Deterministic mapping и crosswalk (UNLOCKER-173) перенесены из E3 в E1, стадия 2, до projector. Candidate matching и конфликты остаются в E3.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| B: mapping/crosswalk в E1, matching в E3 | Projector получает хранимое сопоставление, rebuild воспроизводим | Стадия 2 E1 выросла на подзадачу | Выбран |
| A: всё в E3 (прежний порядок) | Меньше E1 | Projector без хранимого сопоставления, повторная приёмка | Заменён |
| Автоматический merge по сходству | Меньше ручной работы | Ложные пути зависимостей | Отклонён |

## Последствия

- **Положительные:** Критерии 1–4 закрываются в E1; F1 и F2 не ждут E3.
- **Отрицательные / ограничения:** Таблица сопоставления только одна: собственная в projector запрещена.
- **Затронутый код:** `ingestion/identity-resolution`, PostgreSQL crosswalk

## Связанные ADR

[0003](0003-gid-and-source-record-identity.md), [0020](0020-tombstone-instead-of-hard-delete.md)
