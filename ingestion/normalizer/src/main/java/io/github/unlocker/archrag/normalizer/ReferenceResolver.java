package io.github.unlocker.archrag.normalizer;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;

/**
 * Порт проверки ссылок: известен ли системе source record с таким ключом. Реализация (поиск в
 * проекции или crosswalk) подключается снаружи; normalizer сам хранилищ не знает.
 */
@FunctionalInterface
public interface ReferenceResolver {

  /** {@code true}, если record с ключом {@code key} уже известен. */
  boolean exists(SourceKey key);
}
