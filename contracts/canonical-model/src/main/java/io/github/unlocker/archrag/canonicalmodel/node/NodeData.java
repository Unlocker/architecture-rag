package io.github.unlocker.archrag.canonicalmodel.node;

/**
 * Properties of a canonical node, without {@code gid} and lifecycle (see {@link CanonicalNode}).
 *
 * <p>One record per node type. Required fields reject {@code null} and blank strings; optional
 * fields may be {@code null} but not blank.
 */
public sealed interface NodeData
        permits ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance,
        Namespace, KubernetesCluster {

    /** The Neo4j label of this node. */
    NodeLabel label();
}
