package io.github.unlocker.archrag.identityresolution;

/** Кандидат уже не {@code OPEN} (подтверждён ранее); состояние не менялось. */
public class CandidateNotOpenException extends RuntimeException {

  public CandidateNotOpenException(String message) {
    super(message);
  }
}
