package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.util.Objects;

/** Environment. All fields required; {@code code} is the normalized identity key. */
public record Environment(String code, String name, EnvironmentClass environmentClass) implements NodeData {

    public Environment {
        requireText(code, "code");
        requireText(name, "name");
        Objects.requireNonNull(environmentClass, "environmentClass");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.ENVIRONMENT;
    }
}
