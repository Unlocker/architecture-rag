package io.github.unlocker.archrag.sourcespi;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;

/** Мастер-системы PoC. {@link #code()} — значение {@code source} в событиях и ключах. */
public enum SourceSystem {
  EAM(SourceSystemCode.EAM),
  SCM(SourceSystemCode.SCM),
  CMDB(SourceSystemCode.CMDB),
  /** Deploy map и Helm charts; формат пока временный, см. {@code stub.DeployMapFormat}. */
  DEPLOY_MAP(SourceSystemCode.DEPLOYMAP);

  private final SourceSystemCode systemCode;

  SourceSystem(SourceSystemCode systemCode) {
    this.systemCode = systemCode;
  }

  /** Код системы в канонической модели. */
  public SourceSystemCode systemCode() {
    return systemCode;
  }

  /** Стабильный строковый код источника; делегирует {@link SourceSystemCode#code()}. */
  public String code() {
    return systemCode.code();
  }
}
