package io.github.unlocker.archrag.sourcespi;

/** Мастер-системы PoC. {@link #code()} — значение {@code source} в событиях и ключах. */
public enum SourceSystem {
  EAM("eam"),
  SCM("scm"),
  CMDB("cmdb"),
  /** Deploy map и Helm charts; формат пока временный, см. {@code stub.DeployMapFormat}. */
  DEPLOY_MAP("deploymap");

  private final String code;

  SourceSystem(String code) {
    this.code = code;
  }

  /** Стабильный строковый код источника. */
  public String code() {
    return code;
  }
}
