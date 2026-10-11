package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * IT solution (EAM {@code itproducts}). Required: {@code name}. Optional: {@code lifecycle} (source vocabulary) and
 * {@code description}. Not to be confused with {@link Lifecycle}, the validity interval of a stored node.
 */
public record Solution(String name, String lifecycle, String description) implements NodeData {

    public Solution {
        requireText(name, "name");
        optionalText(lifecycle, "lifecycle");
        optionalText(description, "description");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.SOLUTION;
    }
}
