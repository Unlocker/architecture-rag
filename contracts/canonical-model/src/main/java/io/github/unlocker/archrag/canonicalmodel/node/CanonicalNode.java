package io.github.unlocker.archrag.canonicalmodel.node;

import java.util.Objects;
import java.util.UUID;

/** A stored canonical node: stable {@code gid}, its data and lifecycle. */
public record CanonicalNode<T extends NodeData>(UUID gid, T data, Lifecycle lifecycle) {

    public CanonicalNode {
        Objects.requireNonNull(gid, "gid");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
}
