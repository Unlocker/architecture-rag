/**
 * Модуль event-journal: реализация {@code EventJournal}/{@code CanonicalEventPublisher} на
 * PostgreSQL и {@code RawPayloadStore} на S3, схема Flyway.
 *
 * <p>Правило зависимостей: {@code adapters/*} компилируются только против интерфейсов из
 * {@code contracts/event-schemas}; реализацию подключают лишь со {@code scope=runtime}.
 * {@code access/*} от этого модуля не зависит никогда: у MCP нет writer-доступа к PostgreSQL и S3.
 */
package io.github.unlocker.archrag.eventjournal;
