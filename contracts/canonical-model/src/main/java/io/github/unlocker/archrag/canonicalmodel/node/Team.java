package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Owner or operator team. Required: {@code name}. Optional: {@code type}. */
public record Team(String name, String type) implements NodeData {

    public Team {
        requireText(name, "name");
        optionalText(type, "type");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.TEAM;
    }
}
