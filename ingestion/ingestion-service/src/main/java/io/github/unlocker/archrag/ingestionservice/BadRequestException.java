package io.github.unlocker.archrag.ingestionservice;

/** Запрос не прошёл проверку параметров; {@code getMessage()} не содержит значений из запроса. */
public class BadRequestException extends RuntimeException {

  public BadRequestException(String message) {
    super(message);
  }
}
