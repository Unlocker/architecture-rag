package io.github.unlocker.archrag.identityresolution;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;

/** Нормализованный порядок пар ключей (его держит только приложение): по (source, sourceType, sourceId) в лексикографическом порядке строк. */
final class SourceKeys {

  private SourceKeys() {}

  static int compare(SourceKey a, SourceKey b) {
    int r = a.source().name().compareTo(b.source().name());
    if (r == 0) {
      r = a.sourceType().compareTo(b.sourceType());
    }
    return r == 0 ? a.sourceId().compareTo(b.sourceId()) : r;
  }
}
