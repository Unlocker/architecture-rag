# Реестр пробелов Detective (🔴 и 🟡)

> Detective, этап R.4 (UNLOCKER-269). Сводка всех расхождений и сомнений из [`domain.md`](domain.md), [`state-machines.md`](state-machines.md), [`permissions.md`](permissions.md) и [`adrs/`](adrs/). Исходники не правились. 🔴 — код расходится с ADR/`docs/` или функция не доделана; 🟡 — вывод по коду, требует решения или подтверждения.

## 1. Ответы на вопросы, оставленные R.3

R.3 передал Detective вопросы в [`database/data-dictionary.md`](database/data-dictionary.md), §8 (строки 187-193), и два пробела в [`database/business-rules.md`](database/business-rules.md), §3 (строки 47-48). «Находка R.3 №5» из плана этапа — п. 5 списка 🔴 в итоговом комментарии R.3 к UNLOCKER-268: «`dlq_entry.replayed_at` и `identity_candidate.status != 'OPEN'` в коде нигде не выставляются» (в файлах R.3 она описана в `database/data-dictionary.md:94-95`). Оба состояния помечены 🔴 ниже.

| Вопрос R.3 | Ответ Detective |
|---|---|
| Почему `dlq_entry.replayed_at` не заполняется? | Недоделка: ADR на такой выбор нет, `UPDATE dlq_entry` в репозитории отсутствует; replay создаёт *новые* строки `replay:*` и исходную запись DLQ не закрывает ([`state-machines.md`](state-machines.md), §2) 🔴 |
| Жизненный цикл `identity_candidate.status`? | Завершён в UNLOCKER-282: `OPEN`/`RESOLVED`, переход делает подтверждение оператором ([`state-machines.md`](state-machines.md), §3) 🟢 |
| Какой бизнес-инвариант за частичным индексом «одна открытая запись DLQ на событие»? | Подстраховка от дубля при гонке двух воркеров и повторном `toDlq`; сегодня повторный карантин одного события возможен только из `RETRYING`, а выхода `QUARANTINED → RETRYING` в коде нет, поэтому индекс работает «про запас»; решения об этом нет ни в одном ADR 🟡 |

## 2. Реестр 🔴

| № | Что | Где | Ссылки |
|---|---|---|---|
| Р-1 | Статус `SUPERSEDED` недостижим: определён в enum и в `FINISHED`, но ни один код в него не переводит; `docs/00-vision.md` и `docs/02-data-model.md` описывают `PROJECTED → SUPERSEDED` | `state-machines.md` §1.5 | `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/ProcessingStatus.java:39`; `docs/00-vision.md:385`; `docs/02-data-model.md:250` |
| Р-2 | Выхода из `QUARANTINED` нет: переход `QUARANTINED → RETRYING` разрешён, `docs/02-data-model.md` называет его «решением оператора», но админский REST такой операции не содержит, диспетчер карантин не выбирает | `state-machines.md` §1.5 | `ProcessingStatus.java:38`; `docs/02-data-model.md:241`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:61-123`; `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:213-216` |
| Р-3 | `dlq_entry.replayed_at` не пишется; метрика открытых записей DLQ не уменьшается после replay (находка R.3 №5) | `state-machines.md` §2 | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:71`; `database/data-dictionary.md:94` |
| Р-4 | ✅ [UNLOCKER-282] было: `identity_candidate.status` не выходил из `OPEN`; нет ни перечисления значений, ни операции для оператора (находка R.3 №5) | `state-machines.md` §3 | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/IdentityCandidate.java:14-22`; `database/data-dictionary.md:95` |
| Р-5 🟢 [UNLOCKER-280: реализовано] | `SyncRun.status` создаётся как `RUNNING` и никогда не переходит в `SUCCEEDED`/`FAILED`, `endedAt` не пишется, хотя enum и поле модели есть | `state-machines.md` §10 | `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/provenance/SyncRunStatus.java:4-6`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:695-700` |
| Р-6 | `docs/02-data-model.md:273` объясняет `RETRYING` как «конец связи ещё не пришёл»; после E1.14 штатный случай — `DeferRelation` без повтора, `UNRESOLVED_ENDPOINT` остаётся только для связи с концом без `gid` | `adrs/retroactive-map.md` §3.3 | `docs/02-data-model.md:273`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:221-223, 589-597` |

## 3. Реестр 🟡

| № | Что | Где |
|---|---|---|
| П-1 | У `RETRYING` нет предела попыток: `attempts` растёт, карантина по числу попыток нет; постоянный `GRAPH_CLIENT_ERROR` повторяется бесконечно | `state-machines.md` §1.5 |
| П-2 | Признак с > 50 совпадениями молча не порождает кандидатов (WARN), `matched` при upsert перезаписывается без него | `domain.md` §3.3; К-6 |
| П-3 | `forget` удаляет признаки записи, но не созданных кандидатов; кандидат остаётся `OPEN` при перестали совпадать признаки | `state-machines.md` §3 |
| П-4 | `PARTIAL`-полнота нормализатором и проектором не учитывается; `PARTIAL` с неполным списком однотипных связей закроет остальные связи типа | `state-machines.md` §7; К-4 |
| П-5 | Первой пришла неавторитетная запись: создаётся «пустой» текущий узел без свойств | `state-machines.md` §6 |
| П-6 | Scope проверяется только для `tools/call`; `initialize` и `tools/list` доступны любому токену с audience MCP | `permissions.md` §3.2 |
| П-7 | ADR 0020: «high-water mark двигается после успешной проекции страницы». Код: курсор poller'а сдвигается после `append` в журнал, после коммита Neo4j сдвигается другой checkpoint (`projector`) | `adrs/retroactive-map.md` §3.2 |
| П-8 | ADR 0020: «другие активные assertions» держат узел; код: только активные MASTER-утверждения | `adrs/retroactive-map.md` §3.1 |
| П-9 | Один scope `architecture.admin` на консоль и admin REST: различаются только audience и `azp`; любой пользователь realm получает токен консоли (принято владельцем 2026-10-08) | `permissions.md` §5; ADR 0026 |
| П-10 | Внутри `architecture.admin` нет ролей: один оператор — все админские права | `permissions.md` §4.3 |
| П-11 | У отложенной связи нет срока годности; повторной проверки после записи нет (допущение одного экземпляра) | `state-machines.md` §8; К-3, К-7 |
| П-12 | Crosswalk меняет `identity_mapping`, граф переписывается только следующим replay/rebuild (для подтверждения кандидата — сразу, UNLOCKER-282) | `domain.md` §3.2 |
| П-13 | Запись `admin_audit` остаётся `STARTED`, если процесс убит или сбой записи итога; автоматического закрытия нет | `state-machines.md` §11; К-8 |
| П-14 | Маскирование аргументов в аудите MCP эвристическое (по именам ключей) | `permissions.md` §3.4 |
| П-15 | `POST /control/snapshot` адаптера не аутентифицируется; защита только сетевая (адаптеры в сетях `edge` и `backend`). Документированное ограничение (`docs/03-interfaces.md:33, 368, 560`) | `permissions.md` §7 |
| П-16 | ADR 0008 не упоминает мастера `DECOMPOSED_INTO` (SCM), хотя он закреплён в матрице | `adrs/retroactive-map.md` §1 |
| П-17 | `PART_OF` и `HOSTED_ON` нетемпоральны намеренно (`95594a6`), но это нигде не записано | `adrs/candidates.md` К-4 |
| П-18 | Версия tombstone `Reconciler` равна применённой, поэтому upsert той же версии объект не воскресит | `state-machines.md` §5 |
| П-19 | Аудит чтений консоли — только лог, не таблица, retention не определён | `permissions.md` §5; К-8 |
| П-20 | Retention `inbox_event`, `dlq_entry`, `admin_audit`, S3 raw не определён (PoC) | `domain.md` §5 |
| П-21 | Учётка Neo4j `archrag_reader` не ограничена на уровне СУБД (Community); запрет записи держит приложение. Ограничение ADR 0005, наследовано из R.3 (`database/data-dictionary.md:141`), не новое расхождение | `permissions.md` §3.3 |
| П-22 | Конфликт нельзя закрыть вручную (`MANUAL` не авторитетен, операции нет). Документированная отложенная работа: `docs/00-vision.md:399`, `docs/02-data-model.md:360` | `state-machines.md` §4 |

## 4. Кандидаты в ADR (кратко)

Полные карточки — [`adrs/candidates.md`](adrs/candidates.md). К-1 версионирование и идемпотентность · К-2 хранилище утверждений и конфликтов · К-3 отложенные связи и alias · К-4 темпоральность и замещение связей · К-5 reconcile через журнал и ручной snapshot · К-6 параметры candidate matching · К-7 один диспетчер и `AdminLock` · К-8 аудит доступа · К-9 webhook как триггер · К-10 словарные значения не угадываются.
