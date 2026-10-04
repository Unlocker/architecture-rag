package io.github.unlocker.archrag.canonicalmodel.node;

import static io.github.unlocker.archrag.canonicalmodel.Invariants.optionalText;
import static io.github.unlocker.archrag.canonicalmodel.Invariants.requireText;

import java.util.Objects;

/**
 * Compute resource (VM, physical server or an untyped CMDB stub).
 *
 * <p>Required: {@code hostname}, {@code kind}. Optional: {@code ip}, {@code os}, {@code state}.
 * {@code hypervisorRef} is allowed only for {@link ComputeKind#VIRTUAL_MACHINE},
 * {@code serialNumber} only for {@link ComputeKind#PHYSICAL_SERVER}.
 */
public record ComputeInstance(String hostname, ComputeKind kind, String ip, String os, String state,
                              String hypervisorRef, String serialNumber) implements NodeData {

    public ComputeInstance {
        requireText(hostname, "hostname");
        Objects.requireNonNull(kind, "kind");
        optionalText(ip, "ip");
        optionalText(os, "os");
        optionalText(state, "state");
        optionalText(hypervisorRef, "hypervisorRef");
        optionalText(serialNumber, "serialNumber");
        if (hypervisorRef != null && kind != ComputeKind.VIRTUAL_MACHINE) {
            throw new IllegalArgumentException("hypervisorRef is allowed only for VIRTUAL_MACHINE");
        }
        if (serialNumber != null && kind != ComputeKind.PHYSICAL_SERVER) {
            throw new IllegalArgumentException("serialNumber is allowed only for PHYSICAL_SERVER");
        }
    }

    @Override
    public NodeLabel label() {
        return switch (kind) {
            case VIRTUAL_MACHINE -> NodeLabel.VIRTUAL_MACHINE;
            case PHYSICAL_SERVER -> NodeLabel.PHYSICAL_SERVER;
            case UNSPECIFIED -> NodeLabel.COMPUTE_INSTANCE;
        };
    }
}
