package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Service. Required: {@code name}. Optional free-text: {@code serviceType}, {@code language}, {@code status}. */
public record Service(String name, String serviceType, String language, String status) implements NodeData {

    public Service {
        requireText(name, "name");
        optionalText(serviceType, "serviceType");
        optionalText(language, "language");
        optionalText(status, "status");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.SERVICE;
    }
}
