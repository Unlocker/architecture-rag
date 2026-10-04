package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;

/**
 * Курсор потока источника. Сдвигается только после коммита транзакции Neo4j.
 *
 * @param source источник
 * @param stream поток внутри источника (например, тип объекта или {@code snapshot})
 * @param cursor непрозрачное значение курсора источника
 * @param updatedAt момент последнего сдвига, UTC
 */
public record Checkpoint(String source, String stream, String cursor, Instant updatedAt) {}
