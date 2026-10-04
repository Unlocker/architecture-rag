package io.github.unlocker.archrag.eventschemas;

/**
 * Результат {@link EventJournal#append}.
 *
 * @param entry запись журнала; при {@code duplicate} — ранее сохранённая, без изменений
 * @param duplicate {@code true}, если событие с таким {@code (source, eventId)} уже было
 */
public record AppendResult(JournalEntry entry, boolean duplicate) {}
