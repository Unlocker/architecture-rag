package io.github.unlocker.archrag.canonicalmodel.relation;

import io.github.unlocker.archrag.canonicalmodel.Invariants;
import java.time.Instant;
import java.util.Objects;

/** Validity interval of a temporal relation. {@code validTo} may be {@code null} (open); otherwise {@code validFrom <= validTo}. */
public record Validity(Instant validFrom, Instant validTo) {

    public Validity {
        Objects.requireNonNull(validFrom, "validFrom");
        if (validTo != null) {
            Invariants.requireNotAfter(validFrom, validTo, "validFrom", "validTo");
        }
    }
}
