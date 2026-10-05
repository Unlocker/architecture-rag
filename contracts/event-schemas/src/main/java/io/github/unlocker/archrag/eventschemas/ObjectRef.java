package io.github.unlocker.archrag.eventschemas;

/**
 * Объект источника: пара {@code (sourceType, sourceId)} внутри одного {@code source}. Идентификатор
 * непрозрачный и может содержать любые символы, поэтому берётся из отдельных полей журнала, а не из id события.
 */
public record ObjectRef(String sourceType, String sourceId) {}
