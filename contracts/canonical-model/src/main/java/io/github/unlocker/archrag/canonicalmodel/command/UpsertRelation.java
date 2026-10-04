package io.github.unlocker.archrag.canonicalmodel.command;

import io.github.unlocker.archrag.canonicalmodel.Invariants;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.util.Map;
import java.util.Objects;

/**
 * Creates or updates a relation between two source-keyed nodes.
 *
 * <p>Invariants: {@code type.allows(fromLabel, toLabel)}; {@code validity} only for temporal types
 * (may be {@code null} otherwise); {@code properties} are validated and copied immutably.
 */
public record UpsertRelation(RelationType type, SourceKey from, NodeLabel fromLabel, SourceKey to,
                             NodeLabel toLabel, Map<String, Object> properties, Validity validity,
                             SourceKey assertedBy) implements GraphCommand {

    public UpsertRelation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(fromLabel, "fromLabel");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(toLabel, "toLabel");
        Objects.requireNonNull(assertedBy, "assertedBy");
        if (!type.allows(fromLabel, toLabel)) {
            throw new IllegalArgumentException(type + " does not allow " + fromLabel + " -> " + toLabel);
        }
        if (validity != null && !type.temporal()) {
            throw new IllegalArgumentException("validity is allowed only for temporal relation " + type);
        }
        properties = Invariants.copyProperties(properties);
    }
}
