package io.github.unlocker.archrag.canonicalmodel.authority;

import static io.github.unlocker.archrag.canonicalmodel.node.NodeLabel.*;
import static io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode.*;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Authority matrix: which source system is the master for a node type, a node property or a relation type.
 *
 * <p>Invariants: lookups are exact and fail closed. A source that is not listed is not authoritative, and
 * a type absent from the matrix (provenance labels and relations are written by the projector itself)
 * has an empty authority set. A property without its own rule inherits the rule of the node label;
 * label supertypes are not consulted. {@link SourceSystemCode#MANUAL} is never authoritative here:
 * manual conflict resolution is stored as a separate assertion (E3). A non-authoritative differing value
 * must produce a {@code Conflict} and must not replace the canonical value.
 *
 * <p>Instances are immutable; {@link #defaults()} holds the PoC matrix (vision, "Provenance и авторитетность").
 */
public final class AuthorityMatrix {

    private static final AuthorityMatrix DEFAULTS = poc();

    private final Map<NodeLabel, Set<SourceSystemCode>> nodes;
    private final Map<NodeLabel, Map<String, Set<SourceSystemCode>>> properties;
    private final Map<RelationType, Set<SourceSystemCode>> relations;

    private AuthorityMatrix(Map<NodeLabel, Set<SourceSystemCode>> nodes,
                            Map<NodeLabel, Map<String, Set<SourceSystemCode>>> properties,
                            Map<RelationType, Set<SourceSystemCode>> relations) {
        this.nodes = nodes;
        this.properties = properties;
        this.relations = relations;
    }

    /** The matrix of the PoC slice. */
    public static AuthorityMatrix defaults() {
        return DEFAULTS;
    }

    /** True if {@code source} is the master for nodes of this label. */
    public boolean isAuthoritative(NodeLabel label, SourceSystemCode source) {
        return nodeAuthorities(label).contains(source);
    }

    /** True if {@code source} is the master for the property; falls back to the node-level rule. */
    public boolean isAuthoritative(NodeLabel label, String property, SourceSystemCode source) {
        return propertyAuthorities(label, property).contains(source);
    }

    /** True if {@code source} is the master for relations of this type. */
    public boolean isAuthoritative(RelationType type, SourceSystemCode source) {
        return relationAuthorities(type).contains(source);
    }

    /** Masters of the node label; empty for labels outside the matrix. */
    public Set<SourceSystemCode> nodeAuthorities(NodeLabel label) {
        return nodes.getOrDefault(label, Set.of());
    }

    /** Masters of the property: its own rule if present, otherwise those of the node label. */
    public Set<SourceSystemCode> propertyAuthorities(NodeLabel label, String property) {
        return properties.getOrDefault(label, Map.of()).getOrDefault(property, nodeAuthorities(label));
    }

    /** Masters of the relation type; empty for relations outside the matrix. */
    public Set<SourceSystemCode> relationAuthorities(RelationType type) {
        return relations.getOrDefault(type, Set.of());
    }

    private static AuthorityMatrix poc() {
        var nodes = new EnumMap<NodeLabel, Set<SourceSystemCode>>(NodeLabel.class);
        nodes.put(IT_SYSTEM, Set.of(EAM));
        nodes.put(TEAM, Set.of(EAM));
        nodes.put(SERVICE, Set.of(SCM));
        nodes.put(REPOSITORY, Set.of(SCM));
        nodes.put(COMPUTE_INSTANCE, Set.of(CMDB));
        nodes.put(VIRTUAL_MACHINE, Set.of(CMDB));
        nodes.put(PHYSICAL_SERVER, Set.of(CMDB));
        nodes.put(DEPLOYMENT, Set.of(DEPLOYMAP));
        nodes.put(ENVIRONMENT, Set.of(DEPLOYMAP));
        nodes.put(NAMESPACE, Set.of(DEPLOYMAP));
        nodes.put(KUBERNETES_CLUSTER, Set.of(DEPLOYMAP));

        // Explicit property rules named in the vision; they restate the node rule today and pin it against drift.
        var properties = new EnumMap<NodeLabel, Map<String, Set<SourceSystemCode>>>(NodeLabel.class);
        properties.put(IT_SYSTEM, Map.of("criticality", Set.of(EAM)));
        properties.put(SERVICE, Map.of("language", Set.of(SCM)));
        properties.put(REPOSITORY, Map.of("url", Set.of(SCM)));
        properties.put(COMPUTE_INSTANCE, Map.of("state", Set.of(CMDB)));
        properties.put(VIRTUAL_MACHINE, Map.of("state", Set.of(CMDB)));
        properties.put(PHYSICAL_SERVER, Map.of("state", Set.of(CMDB)));

        var relations = new EnumMap<RelationType, Set<SourceSystemCode>>(RelationType.class);
        relations.put(RelationType.DEPENDS_ON, Set.of(EAM));
        relations.put(RelationType.OWNED_BY, Set.of(EAM));
        relations.put(RelationType.DECOMPOSED_INTO, Set.of(EAM));
        relations.put(RelationType.IMPLEMENTED_IN, Set.of(SCM));
        relations.put(RelationType.HAS_DEPLOYMENT, Set.of(DEPLOYMAP));
        relations.put(RelationType.IN_ENVIRONMENT, Set.of(DEPLOYMAP));
        relations.put(RelationType.RUNS_ON, Set.of(DEPLOYMAP));
        relations.put(RelationType.PART_OF, Set.of(DEPLOYMAP));
        relations.put(RelationType.HOSTED_ON, Set.of(CMDB));

        return new AuthorityMatrix(Map.copyOf(nodes), copyProperties(properties), Map.copyOf(relations));
    }

    private static Map<NodeLabel, Map<String, Set<SourceSystemCode>>> copyProperties(
            Map<NodeLabel, Map<String, Set<SourceSystemCode>>> source) {
        var copy = new HashMap<NodeLabel, Map<String, Set<SourceSystemCode>>>();
        source.forEach((label, rules) -> copy.put(label, Map.copyOf(rules)));
        return Map.copyOf(copy);
    }
}
