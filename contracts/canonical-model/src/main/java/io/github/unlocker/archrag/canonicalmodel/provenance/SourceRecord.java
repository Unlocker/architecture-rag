package io.github.unlocker.archrag.canonicalmodel.provenance;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.time.Instant;
import java.util.Objects;

/** Provenance of a source object: key, version, content hash, fetch time and whether it is still active. All fields required. */
public record SourceRecord(SourceKey key, String sourceVersion, String contentHash, Instant fetchedAt, boolean active) {

    public SourceRecord {
        Objects.requireNonNull(key, "key");
        requireText(sourceVersion, "sourceVersion");
        requireText(contentHash, "contentHash");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
