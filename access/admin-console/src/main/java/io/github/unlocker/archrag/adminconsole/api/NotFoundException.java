package io.github.unlocker.archrag.adminconsole.api;

/** Запрошенный актив не найден. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
