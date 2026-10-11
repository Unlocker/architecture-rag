package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Service deployment. Required: {@code deploymentKey} (identity key), {@code name}. Optional: {@code version},
 * {@code status}, {@code sourceCommit}, {@code repositoryUrl}, {@code eamSystemId}, {@code observedAt}.
 *
 * <p>Invariants: {@code sourceCommit} is exactly 40 lower-case hex digits; {@code repositoryUrl} is a canonical
 * Git URL ({@link GitUrls#normalize} is idempotent on it); {@code eamSystemId} is the positive integer {@code id}
 * of the EAM system (ADR 0024), not {@code eam_id}.
 */
public record Deployment(String deploymentKey, String name, String version, String status, String sourceCommit,
                         String repositoryUrl, Long eamSystemId, Instant observedAt) implements NodeData {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public Deployment {
        requireText(deploymentKey, "deploymentKey");
        requireText(name, "name");
        optionalText(version, "version");
        optionalText(status, "status");
        if (sourceCommit != null && !COMMIT.matcher(sourceCommit).matches()) {
            throw new IllegalArgumentException("sourceCommit must be 40 lower-case hex digits");
        }
        if (repositoryUrl != null && !repositoryUrl.equals(GitUrls.normalize(repositoryUrl))) {
            throw new IllegalArgumentException("repositoryUrl must be a normalized git url");
        }
        if (eamSystemId != null && eamSystemId <= 0) {
            throw new IllegalArgumentException("eamSystemId must be positive");
        }
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.DEPLOYMENT;
    }
}
