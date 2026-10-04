/**
 * Общий код адаптеров мастер-систем: REST-коннектор, приём подписанного webhook, polling с
 * backoff/jitter/Retry-After и полный snapshot. Зависит только от {@code contracts/source-spi} и
 * {@code contracts/event-schemas}; журнал и хранилище raw приходят через интерфейсы. Neo4j-доступа
 * и source DTO за пределами адаптера нет.
 *
 * <h2>Контракт событий (для normalizer, UNLOCKER-167/168, и reconciliation, UNLOCKER-169)</h2>
 *
 * <p>{@code source} события, ключа дедупликации и checkpoint — {@code urn:corp:<code>}, например
 * {@code urn:corp:eam}.
 *
 * <ul>
 *   <li>{@code architecture.asset.upserted.v1} — состояние объекта. Значения {@code null} из
 *       источника в {@code data.payload} не попадают (поле не передано); {@code
 *       data.payload._completeness} — {@code COMPLETE} или {@code PARTIAL}. Для {@code PARTIAL}
 *       отсутствующие поля не означают удаление.
 *   <li>{@code architecture.asset.deleted.v1} — tombstone объекта источника, payload пуст.
 *   <li><b>Маркер snapshot-complete</b>: событие {@code architecture.sync.snapshot-complete.v1},
 *       id {@code snapshot-complete:<syncRunId>}, subject {@code sync-run/<syncRunId>}, {@code
 *       data.sourceType = SYNC_RUN}, {@code data.sourceId = <syncRunId>}, {@code
 *       data.payload = {syncRunId, objectCount}}. Оно пишется в inbox <em>после</em> всех событий
 *       snapshot и несёт тот же {@code syncRunId} в столбце {@code sync_run_id}. Missing set
 *       прогона можно считать, только когда такое событие есть и обработано; до этого удалять
 *       нельзя. Все события snapshot имеют id {@code snap:<syncRunId>:<type>/<id>/v<version>} и
 *       {@code sync_run_id = <syncRunId>}. Это обычные события {@code upserted}: нормализатор
 *       обязан обрабатывать маркер по {@code type} и не проецировать его как актив.
 *   <li>Идентификаторы событий polling — {@code poll:<type>/<id>/v<version>}, webhook — {@code
 *       eventId} источника. Они детерминированы, поэтому перечитывание страницы после сбоя даёт
 *       {@code DUPLICATE}, а не вторую строку.
 * </ul>
 *
 * <p>Если snapshot прервался, следующий цикл начинается с начала под тем же {@code syncRunId}
 * (он хранится в checkpoint {@code <code>-snapshot}); незавершённый прогон маркера не получает.
 *
 * <p>Webhook: {@code 202} только после записи в inbox; повтор тела даёт {@code DUPLICATE} и тоже
 * {@code 202}. Если источник не знает объект после уведомления ({@code fetchById} пуст), ответ
 * {@code 404} и ничего не записывается.
 */
package io.github.unlocker.archrag.adaptercore;
