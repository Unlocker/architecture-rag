package io.github.unlocker.archrag.canonicalmodel.provenance;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.util.Objects;

/** Key of a source record: {@code (source, sourceType, sourceId)}. {@code sourceId} is an opaque non-blank string. */
public record SourceKey(SourceSystemCode source, String sourceType, String sourceId) {

    public SourceKey {
        Objects.requireNonNull(source, "source");
        requireText(sourceType, "sourceType");
        requireText(sourceId, "sourceId");
    }
}
