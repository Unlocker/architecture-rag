package io.github.unlocker.archrag.canonicalmodel.command;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import java.time.Instant;
import java.util.Objects;

/** Marks a source record as deleted at {@code deletedAt}. Must be issued only after a complete snapshot. */
public record TombstoneSourceRecord(SourceKey key, Instant deletedAt) implements GraphCommand {

    public TombstoneSourceRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(deletedAt, "deletedAt");
    }
}
