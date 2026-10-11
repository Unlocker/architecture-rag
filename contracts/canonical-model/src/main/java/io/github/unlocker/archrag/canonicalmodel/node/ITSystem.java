package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * Accounting IT system. Required: {@code name}. Optional: {@code status} (source vocabulary), {@code criticality},
 * {@code description}, {@code eamCode} (the EAM system code {@code eam_id}, e.g. {@code EAM-1042}, by which SCM refers
 * to the system) and {@code criticalityRaw} (the source value of a criticality the dictionary does not know; set
 * instead of {@code criticality}).
 */
public record ITSystem(String name, String status, Criticality criticality, String description, String eamCode,
                       String criticalityRaw) implements NodeData {

    public ITSystem {
        requireText(name, "name");
        optionalText(status, "status");
        optionalText(description, "description");
        optionalText(eamCode, "eamCode");
        optionalText(criticalityRaw, "criticalityRaw");
    }

    /** System without the EAM code and without an unmapped criticality value. */
    public ITSystem(String name, String status, Criticality criticality, String description) {
        this(name, status, criticality, description, null, null);
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.IT_SYSTEM;
    }
}
