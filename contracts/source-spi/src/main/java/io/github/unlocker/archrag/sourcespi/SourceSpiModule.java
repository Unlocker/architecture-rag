package io.github.unlocker.archrag.sourcespi;

/** Маркер модуля source-spi: без него JAR пустой, а smoke-тест не проверяет classpath. */
public final class SourceSpiModule {

  private SourceSpiModule() {}
}
