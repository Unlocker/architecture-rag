package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Kubernetes namespace (modelled, not populated in the PoC). Required: {@code name}. */
public record Namespace(String name) implements NodeData {

    public Namespace {
        requireText(name, "name");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.NAMESPACE;
    }
}
