package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.time.Instant;

/** Service deployment. Required: {@code deploymentKey} (identity key), {@code name}. Optional: {@code version}, {@code status}, {@code observedAt}. */
public record Deployment(String deploymentKey, String name, String version, String status, Instant observedAt)
        implements NodeData {

    public Deployment {
        requireText(deploymentKey, "deploymentKey");
        requireText(name, "name");
        optionalText(version, "version");
        optionalText(status, "status");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.DEPLOYMENT;
    }
}
