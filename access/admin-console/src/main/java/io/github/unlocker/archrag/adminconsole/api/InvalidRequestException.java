package io.github.unlocker.archrag.adminconsole.api;

/** Параметры запроса не прошли проверку; сообщение не содержит введённых значений. */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
