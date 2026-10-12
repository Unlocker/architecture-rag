package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * IT solution (EAM {@code itproducts}). Required: {@code name}. Optional: {@code lifecycle} (source vocabulary),
 * {@code description} and {@code eamPlatformId} (the EAM {@code itproducts.platform_id}, positive; the platform that
 * the platforms of the solution's systems are checked against, see ADR 0031). Not to be confused with
 * {@link Lifecycle}, the validity interval of a stored node.
 */
public record Solution(String name, String lifecycle, String description, Long eamPlatformId) implements NodeData {

    public Solution {
        requireText(name, "name");
        optionalText(lifecycle, "lifecycle");
        optionalText(description, "description");
        if (eamPlatformId != null && eamPlatformId <= 0) {
            throw new IllegalArgumentException("eamPlatformId must be positive");
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
