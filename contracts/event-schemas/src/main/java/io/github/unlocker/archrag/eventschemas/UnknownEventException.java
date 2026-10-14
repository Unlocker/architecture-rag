package io.github.unlocker.archrag.eventschemas;

/** События нет в журнале; бросает {@link EventJournal#releaseFromQuarantine}. */
public final class UnknownEventException extends IllegalArgumentException {

  public UnknownEventException(String message) {
    super(message);
  }
}
