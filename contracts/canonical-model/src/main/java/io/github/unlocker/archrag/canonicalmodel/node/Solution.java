package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * IT solution (EAM {@code itproducts}). Required: {@code name}. Optional: {@code lifecycle} (source vocabulary) and
 * {@code description}, {@code platformId} (the EAM {@code itproducts.platform_id}, positive; the reference
 * that the platform of the solution's systems is checked against, see ADR 0031). Not to be confused with {@link Lifecycle}, the validity interval of a stored node.
 */
public record Solution(String name, String lifecycle, String description, Long platformId) implements NodeData {

    public Solution {
        requireText(name, "name");
        optionalText(lifecycle, "lifecycle");
        optionalText(description, "description");
        if (platformId != null && platformId <= 0) {
            throw new IllegalArgumentException("platformId must be positive");
        }
    }

    /** Solution without the EAM platform reference. */
    public Solution(String name, String lifecycle, String description) {
        this(name, lifecycle, description, null);
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.SOLUTION;
    }
}
