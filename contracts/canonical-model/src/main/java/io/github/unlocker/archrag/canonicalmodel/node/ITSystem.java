package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Accounting IT system. Required: {@code name}. Optional: {@code status} (source vocabulary), {@code criticality}, {@code description}. */
public record ITSystem(String name, String status, Criticality criticality, String description) implements NodeData {

    public ITSystem {
        requireText(name, "name");
        optionalText(status, "status");
        optionalText(description, "description");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.IT_SYSTEM;
    }
}
