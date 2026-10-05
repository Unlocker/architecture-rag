package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/** Source code repository. Required: {@code url}. Optional: {@code defaultBranch}. {@code archived} is {@code null} when unknown (an unknown value must not overwrite a known one). */
public record Repository(String url, String defaultBranch, Boolean archived) implements NodeData {

    public Repository {
        requireText(url, "url");
        optionalText(defaultBranch, "defaultBranch");
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.REPOSITORY;
    }
}
