package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;

/**
 * Курсор consumer по источнику. Сдвигается только после коммита транзакции Neo4j.
 *
 * @param consumer имя потребителя (например, {@code projector})
 * @param source источник
 * @param cursor непрозрачное значение курсора
 * @param updatedAt момент последнего сдвига, UTC
 */
public record Checkpoint(String consumer, String source, String cursor, Instant updatedAt) {}
