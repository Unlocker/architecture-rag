package io.github.unlocker.archrag.ingestionservice;

/** Adapter-сервис недоступен или ответил неожиданным статусом (в сообщении ни адреса, ни тела ответа). */
public class AdapterUnavailableException extends RuntimeException {

  public AdapterUnavailableException() {
    super("adapter is unavailable");
  }
}
