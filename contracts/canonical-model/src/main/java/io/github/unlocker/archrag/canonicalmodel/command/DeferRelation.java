package io.github.unlocker.archrag.canonicalmodel.command;

import java.util.Objects;

/**
 * Defers a relation whose endpoint is not in the graph yet; the projector stores it and builds it with
 * {@link UpsertRelation} semantics when every endpoint becomes known.
 *
 * <p>Invariants: {@code relation} is a valid {@link UpsertRelation}; at least one of its endpoints may still be
 * unknown, so the request does not need a {@code gid} for it.
 */
public record DeferRelation(UpsertRelation relation) implements GraphCommand {

    public DeferRelation {
        Objects.requireNonNull(relation, "relation");
    }
}
