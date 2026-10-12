package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * IT platform (EAM {@code platforms}). Required: {@code name}. Optional: {@code type}, {@code version},
 * {@code lifecycle} (source vocabulary) and {@code description}.
 */
public record Platform(String name, String type, String version, String lifecycle, String description)
        implements NodeData {

    public Platform {
        requireText(name, "name");
        optionalText(type, "type");
        optionalText(version, "version");
        optionalText(lifecycle, "lifecycle");
        optionalText(description, "description");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.PLATFORM;
    }
}
