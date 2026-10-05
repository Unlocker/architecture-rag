package io.github.unlocker.archrag.canonicalmodel.provenance;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import io.github.unlocker.archrag.canonicalmodel.Invariants;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter run. {@code endedAt} may be {@code null} while running, otherwise {@code startedAt <= endedAt}.
 * Counters are non-negative.
 */
public record SyncRun(UUID runId, String adapter, Instant startedAt, Instant endedAt, SyncRunStatus status,
                      long fetched, long applied, long failed) {

    public SyncRun {
        Objects.requireNonNull(runId, "runId");
        requireText(adapter, "adapter");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(status, "status");
        if (endedAt != null) {
            Invariants.requireNotAfter(startedAt, endedAt, "startedAt", "endedAt");
        }
        if (fetched < 0 || applied < 0 || failed < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
    }
}
