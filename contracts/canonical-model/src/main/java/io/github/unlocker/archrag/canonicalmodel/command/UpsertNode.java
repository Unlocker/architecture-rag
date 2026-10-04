package io.github.unlocker.archrag.canonicalmodel.command;

import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import java.util.Objects;

/** Creates or updates the canonical node asserted by {@code record}. */
public record UpsertNode(SourceRecord record, NodeData data) implements GraphCommand {

    public UpsertNode {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(data, "data");
    }
}
