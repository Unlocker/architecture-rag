package io.github.unlocker.archrag.canonicalmodel.node;

import io.github.unlocker.archrag.canonicalmodel.Invariants;
import java.time.Instant;
import java.util.Objects;

/**
 * Lifecycle fields of a stored node (UTC instants).
 *
 * <p>Invariants: {@code firstSeenAt <= lastSeenAt}; {@code isCurrent == (deletedAt == null)}.
 */
public record Lifecycle(Instant firstSeenAt, Instant lastSeenAt, Instant deletedAt, boolean isCurrent) {

    public Lifecycle {
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Invariants.requireNotAfter(firstSeenAt, lastSeenAt, "firstSeenAt", "lastSeenAt");
        if (isCurrent != (deletedAt == null)) {
            throw new IllegalArgumentException("isCurrent must be true exactly when deletedAt is null");
        }
    }
}
