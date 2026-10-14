package io.github.unlocker.archrag.identityresolution;

/** Для пары ключей нет кандидата на совпадение; состояние не менялось. */
public class CandidateNotFoundException extends RuntimeException {

  public CandidateNotFoundException(String message) {
    super(message);
  }
}
