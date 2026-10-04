package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Kubernetes cluster (modelled, not populated in the PoC). Required: {@code name}. Optional: {@code version}. */
public record KubernetesCluster(String name, String version) implements NodeData {

    public KubernetesCluster {
        requireText(name, "name");
        optionalText(version, "version");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.KUBERNETES_CLUSTER;
    }
}
