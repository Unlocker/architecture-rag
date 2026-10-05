package io.github.unlocker.archrag.canonicalmodel.relation;

import static io.github.unlocker.archrag.canonicalmodel.node.NodeLabel.*;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import java.util.Set;

/**
 * Relation types of the PoC with allowed source/target labels and the temporal flag.
 * Label checks honour label supertypes ({@code RUNS_ON -> VirtualMachine} is allowed via {@code ComputeInstance}).
 */
public enum RelationType {
    DECOMPOSED_INTO(Set.of(IT_SYSTEM), Set.of(SERVICE), false),
    IMPLEMENTED_IN(Set.of(SERVICE), Set.of(REPOSITORY), false),
    HAS_DEPLOYMENT(Set.of(SERVICE), Set.of(DEPLOYMENT), false),
    IN_ENVIRONMENT(Set.of(DEPLOYMENT), Set.of(ENVIRONMENT), false),
    RUNS_ON(Set.of(DEPLOYMENT), Set.of(COMPUTE_INSTANCE, NAMESPACE), true),
    PART_OF(Set.of(NAMESPACE), Set.of(KUBERNETES_CLUSTER), false),
    HOSTED_ON(Set.of(VIRTUAL_MACHINE), Set.of(PHYSICAL_SERVER), false),
    DEPENDS_ON(Set.of(SERVICE), Set.of(SERVICE), true),
    OWNED_BY(Set.of(IT_SYSTEM, SERVICE), Set.of(TEAM), true),
    ASSERTS(Set.of(SOURCE_RECORD), NodeLabel.canonical(), false),
    OWNS_RECORD(Set.of(SOURCE_SYSTEM), Set.of(SOURCE_RECORD), false),
    PROCESSED(Set.of(SYNC_RUN), Set.of(SOURCE_RECORD), false);

    private final Set<NodeLabel> sources;
    private final Set<NodeLabel> targets;
    private final boolean temporal;

    RelationType(Set<NodeLabel> sources, Set<NodeLabel> targets, boolean temporal) {
        this.sources = Set.copyOf(sources);
        this.targets = Set.copyOf(targets);
        this.temporal = temporal;
    }

    /** True if the relation carries a validity interval and can be closed. */
    public boolean temporal() {
        return temporal;
    }

    /** Allowed source labels (as declared, without subtypes). */
    public Set<NodeLabel> sources() {
        return sources;
    }

    /** Allowed target labels (as declared, without subtypes). */
    public Set<NodeLabel> targets() {
        return targets;
    }

    /** True if {@code from -> to} is permitted, taking label supertypes into account. */
    public boolean allows(NodeLabel from, NodeLabel to) {
        return sources.stream().anyMatch(from::isA) && targets.stream().anyMatch(to::isA);
    }
}
