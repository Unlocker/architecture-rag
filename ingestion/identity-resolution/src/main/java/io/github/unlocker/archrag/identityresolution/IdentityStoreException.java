package io.github.unlocker.archrag.identityresolution;

/** Ошибка хранилища mapping/crosswalk (SQL или неконсистентное состояние); исходная причина сохраняется. */
public class IdentityStoreException extends RuntimeException {

  public IdentityStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
