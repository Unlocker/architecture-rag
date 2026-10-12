package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

/**
 * Accounting IT system. Required: {@code name}. Optional: {@code status} (source vocabulary), {@code criticality},
 * {@code description}, {@code eamCode} (the EAM system code {@code eam_id}, e.g. {@code EAM-1042}, by which SCM refers
 * to the system) and {@code criticalityRaw} (the source value of a criticality the dictionary does not know; set
 * instead of {@code criticality}) and {@code eamPlatformId} (the EAM {@code itsystems.platform_id}, positive; must
 * equal the platform of the system's solution, see ADR 0031).
 */
public record ITSystem(String name, String status, Criticality criticality, String description, String eamCode,
                       String criticalityRaw, Long eamPlatformId) implements NodeData {

    public ITSystem {
        requireText(name, "name");
        optionalText(status, "status");
        optionalText(description, "description");
        optionalText(eamCode, "eamCode");
        optionalText(criticalityRaw, "criticalityRaw");
        if (eamPlatformId != null && eamPlatformId <= 0) {
            throw new IllegalArgumentException("eamPlatformId must be positive");
        }
    }

    /** System without the EAM platform reference. */
    public ITSystem(String name, String status, Criticality criticality, String description, String eamCode,
                    String criticalityRaw) {
        this(name, status, criticality, description, eamCode, criticalityRaw, null);
    }

    /** System without the EAM code and without an unmapped criticality value. */
    public ITSystem(String name, String status, Criticality criticality, String description) {
        this(name, status, criticality, description, null, null, null);
    }

    @Override
    public NodeLabel label() {
        return NodeLabel.IT_SYSTEM;
    }
}
