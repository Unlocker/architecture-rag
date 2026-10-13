# User stories: оператор управляет ingestion

> Основание: [`../ingestion-service/requirements.md`](../ingestion-service/requirements.md), [`../ingestion-service/contracts.md`](../ingestion-service/contracts.md). OpenAPI: [`../openapi/ingestion-admin.yaml`](../openapi/ingestion-admin.yaml).

## US-O1. Переиграть журнал за период
Как оператор, я хочу `POST /admin/replay` с `source` и диапазоном, чтобы восстановить проекцию после сбоя; повтор с тем же `replayId` идемпотентен 🟢.
- Отказ: `source` отсутствует, `receivedFrom ≥ receivedTo`, `replayId` не UUID — `400`; другая операция выполняется — `409` 🟢.
- Спеки: [`../ingestion-service/replay-rebuild/`](../ingestion-service/replay-rebuild/requirements.md).

## US-O2. Пересобрать граф с нуля
Как оператор, я хочу `POST /admin/rebuild?confirm=true`, чтобы стереть граф и переиграть весь журнал; без `confirm=true` — `400` 🟢. 🔴 Битый raw ломает rebuild после стирания графа (Q-04 `../ingestion-service/questions.md`).

## US-O3. Запустить сверку источника
Как оператор, я хочу `POST /admin/reconcile/{source}`, чтобы адаптер выполнил полный snapshot; удаления выполняются только по маркеру `snapshot-complete` 🟢.
- Спеки: [`../ingestion-service/reconcile-source/`](../ingestion-service/reconcile-source/requirements.md).

## US-O4. Подтвердить идентичность вручную
Как оператор, я хочу `POST /admin/crosswalks` (1..1000 пар), чтобы связать записи разных источников в один актив без авто-слияния; исход по каждому элементу — `APPLIED`/`CONFLICT`/`INVALID` 🟢.
- Спеки: [`../ingestion-service/load-crosswalk/`](../ingestion-service/load-crosswalk/requirements.md), [`../identity-resolution/`](../identity-resolution/requirements.md).

## US-O5. Аудит и безопасность операций
Как владелец платформы, я хочу, чтобы каждая админская операция оставляла запись `admin_audit` (`STARTED` → итог), требовала scope `architecture.admin` и `azp` из allow-list, и не выполнялась параллельно 🟢.

## US-O6. Автоматическая обработка журнала
Как платформа, я хочу, чтобы диспетчер последовательно проводил события `RECEIVED`/`RETRYING` через процессор, а непригодные строки уходили в DLQ с кодом 🟢. 🔴 Предела повторов нет (Q-01).
- Спеки: [`../ingestion-service/dispatch-journal/`](../ingestion-service/dispatch-journal/requirements.md).
