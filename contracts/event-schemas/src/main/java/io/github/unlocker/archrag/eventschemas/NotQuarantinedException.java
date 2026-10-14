package io.github.unlocker.archrag.eventschemas;

/** Событие не в {@code QUARANTINED}; бросает {@link EventJournal#releaseFromQuarantine}. */
public final class NotQuarantinedException extends IllegalStateException {

  public NotQuarantinedException(String message) {
    super(message);
  }
}
