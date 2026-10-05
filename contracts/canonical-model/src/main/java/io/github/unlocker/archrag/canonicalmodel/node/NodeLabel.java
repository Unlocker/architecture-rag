package io.github.unlocker.archrag.canonicalmodel.node;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Neo4j node labels of the PoC slice, with the label supertype.
 *
 * <p>{@link #VIRTUAL_MACHINE} and {@link #PHYSICAL_SERVER} are subtypes of {@link #COMPUTE_INSTANCE}.
 * Provenance labels ({@code SourceSystem}, {@code SourceRecord}, {@code SyncRun}) are not canonical.
 */
public enum NodeLabel {
    IT_SYSTEM("ITSystem"),
    SERVICE("Service"),
    REPOSITORY("Repository"),
    TEAM("Team"),
    ENVIRONMENT("Environment"),
    DEPLOYMENT("Deployment"),
    COMPUTE_INSTANCE("ComputeInstance"),
    VIRTUAL_MACHINE("VirtualMachine", COMPUTE_INSTANCE),
    PHYSICAL_SERVER("PhysicalServer", COMPUTE_INSTANCE),
    NAMESPACE("Namespace"),
    KUBERNETES_CLUSTER("KubernetesCluster"),
    SOURCE_SYSTEM("SourceSystem"),
    SOURCE_RECORD("SourceRecord"),
    SYNC_RUN("SyncRun");

    private final String label;
    private final NodeLabel supertype;

    NodeLabel(String label) {
        this(label, null);
    }

    NodeLabel(String label, NodeLabel supertype) {
        this.label = label;
        this.supertype = supertype;
    }

    /** The label string as stored in Neo4j. */
    public String label() {
        return label;
    }

    /** The supertype label, or {@code null} if there is none. */
    public NodeLabel supertype() {
        return supertype;
    }

    /** True if this label equals {@code other} or is a (transitive) subtype of it. */
    public boolean isA(NodeLabel other) {
        for (NodeLabel current = this; current != null; current = current.supertype) {
            if (current == other) {
                return true;
            }
        }
        return false;
    }

    /** True for labels of canonical (non-provenance) nodes. */
    public boolean isCanonical() {
        return this != SOURCE_SYSTEM && this != SOURCE_RECORD && this != SYNC_RUN;
    }

    /** All canonical labels. */
    public static Set<NodeLabel> canonical() {
        return EnumSet.copyOf(Arrays.stream(values()).filter(NodeLabel::isCanonical).toList());
    }
}
