# User stories: владелец мастер-системы подключает источник

> Основание: [`../source-spi/requirements.md`](../source-spi/requirements.md), [`../adapter-core/requirements.md`](../adapter-core/requirements.md), адаптеры [`eam`](../eam-adapter/requirements.md), [`scm`](../scm-adapter/requirements.md), [`asset`](../asset-adapter/requirements.md), [`deploymap`](../deploymap-adapter/requirements.md). OpenAPI: [`../openapi/adapter-http.yaml`](../openapi/adapter-http.yaml).

## US-S1. Подключить источник через SPI
Как владелец источника, я хочу реализовать `SourceConnector` (`fetchChanges`, `fetchById`, проверка подписи webhook), чтобы адаптер сам занимался опросом, повторами и записью в журнал 🟢. Спеки: [`../source-spi/`](../source-spi/requirements.md).

## US-S2. Получать изменения опросом
Как платформа, я хочу инкрементальный опрос с курсором, который сдвигается только после записи в журнал, и дедупликацией по `source + eventId` 🟢. Спеки: [`../adapter-core/incremental-poll/`](../adapter-core/incremental-poll/requirements.md).

## US-S3. Получать уведомления через подписанный webhook
Как источник, я хочу `POST /webhook` с HMAC-подписью; неверная подпись/окно — 401, большое тело — 413, неизвестный объект — 404 🟢. Спеки: [`../adapter-core/receive-webhook/`](../adapter-core/receive-webhook/requirements.md).

## US-S4. Полная сверка и удаление пропавших
Как платформа, я хочу полный snapshot с маркером `snapshot-complete`, после которого — и только после него — закрываются объекты, пропавшие из источника 🟢. Спеки: [`../adapter-core/full-snapshot/`](../adapter-core/full-snapshot/requirements.md).

## US-S5. Переживать сбои источника
Как платформа, я хочу ограниченные повторы запроса к источнику и `503` при его недоступности вместо потери события 🟢. Спеки: [`../adapter-core/source-fetch-retry/`](../adapter-core/source-fetch-retry/requirements.md).
