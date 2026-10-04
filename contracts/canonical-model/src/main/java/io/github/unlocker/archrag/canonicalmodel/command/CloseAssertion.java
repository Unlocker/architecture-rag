package io.github.unlocker.archrag.canonicalmodel.command;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.time.Instant;
import java.util.Objects;

/** Closes (sets {@code validTo}) the temporal relation asserted by {@code assertedBy}. Temporal types only. */
public record CloseAssertion(RelationType type, SourceKey from, SourceKey to, Instant validTo,
                             SourceKey assertedBy) implements GraphCommand {

    public CloseAssertion {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(validTo, "validTo");
        Objects.requireNonNull(assertedBy, "assertedBy");
        if (!type.temporal()) {
            throw new IllegalArgumentException("only temporal relations can be closed: " + type);
        }
    }
}
