package io.github.unlocker.archrag.canonicalmodel.provenance;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.util.Objects;

/** Source system node: code and display name, both required. */
public record SourceSystem(SourceSystemCode code, String name) {

    public SourceSystem {
        Objects.requireNonNull(code, "code");
        requireText(name, "name");
    }
}
