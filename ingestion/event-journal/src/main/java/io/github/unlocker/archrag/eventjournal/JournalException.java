package io.github.unlocker.archrag.eventjournal;

/** Ошибка хранилища журнала; причина (SQLException) сохраняется, ошибки не глушатся. */
public class JournalException extends RuntimeException {

  public JournalException(String message, Throwable cause) {
    super(message, cause);
  }
}
