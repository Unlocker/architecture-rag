package io.github.unlocker.archrag.canonicalmodel.command;

/**
 * Projection command produced by the normalizer and consumed by the graph projector.
 * Commands reference nodes by {@code SourceKey}; {@code gid} is assigned later by identity resolution.
 */
public sealed interface GraphCommand
        permits UpsertNode, UpsertRelation, CloseAssertion, TombstoneSourceRecord {
}
