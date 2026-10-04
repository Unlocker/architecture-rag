package io.github.unlocker.archrag.ingestionservice;

/** Другая админская операция уже выполняется (advisory lock занят). */
public class AdminBusyException extends RuntimeException {

  public AdminBusyException() {
    super("another admin operation is in progress");
  }
}
