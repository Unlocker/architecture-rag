package io.github.unlocker.archrag.scmadapter;

/** Маркер модуля scm-adapter: без него JAR пустой, а smoke-тест не проверяет classpath. */
public final class ScmAdapterModule {

  private ScmAdapterModule() {}
}
